package edu.smarttraffic.processor.adapter.out.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.kafkaserdes.JsonSerde;
import edu.smarttraffic.processor.application.port.out.TrafficUpdatePublisher;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;

public final class RabbitMqOAuthTrafficUpdatePublisher implements TrafficUpdatePublisher, AutoCloseable {
    private static final long PUBLISH_CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final Configuration configuration;
    private final RabbitMqOAuthClient oauthClient;
    private final JsonSerde<TrafficUpdate> trafficUpdateSerde = new JsonSerde<>(TrafficUpdate.class);
    private final AtomicReference<String> returnedMessage = new AtomicReference<>();

    private Connection connection;
    private Channel channel;
    private String accessToken;
    private Instant tokenRefreshAt = Instant.MIN;

    public RabbitMqOAuthTrafficUpdatePublisher(Map<String, String> environment) {
        configuration = Configuration.from(environment);
        oauthClient = new RabbitMqOAuthClient(environment);
    }

    public static RabbitMqOAuthTrafficUpdatePublisher fromEnvironment() {
        return new RabbitMqOAuthTrafficUpdatePublisher(System.getenv());
    }

    @Override
    public synchronized void publish(TrafficUpdate update) {
        Objects.requireNonNull(update, "Traffic update is required");

        try {
            ensureConnection();
            byte[] body = trafficUpdateSerde.serializer().serialize(configuration.exchange(), update);
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .build();
            returnedMessage.set(null);
            channel.basicPublish(
                    configuration.exchange(),
                    configuration.routingKey(),
                    true,
                    properties,
                    body);
            channel.waitForConfirmsOrDie(PUBLISH_CONFIRM_TIMEOUT_MILLIS);
            String returnDetails = returnedMessage.getAndSet(null);
            if (returnDetails != null) {
                throw new IllegalStateException(
                        "RabbitMQ returned traffic update as unroutable: " + returnDetails);
            }
        } catch (IOException | TimeoutException exception) {
            throw new IllegalStateException("Could not publish traffic update to RabbitMQ", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing traffic update to RabbitMQ", exception);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        IOException closeFailure = null;
        Channel activeChannel = channel;
        channel = null;
        if (activeChannel != null && activeChannel.isOpen()) {
            try {
                activeChannel.close();
            } catch (IOException exception) {
                closeFailure = exception;
            } catch (TimeoutException exception) {
                closeFailure = new IOException("Timed out while closing RabbitMQ channel", exception);
            }
        }

        Connection activeConnection = connection;
        connection = null;
        accessToken = null;
        tokenRefreshAt = Instant.MIN;
        if (activeConnection != null && activeConnection.isOpen()) {
            try {
                activeConnection.close();
            } catch (IOException exception) {
                if (closeFailure == null) {
                    closeFailure = exception;
                } else {
                    closeFailure.addSuppressed(exception);
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void ensureConnection() throws IOException, InterruptedException, TimeoutException {
        Instant now = Instant.now();
        if (connection != null
                && connection.isOpen()
                && channel != null
                && channel.isOpen()
                && now.isBefore(tokenRefreshAt)) {
            return;
        }

        closeActiveConnection();
        returnedMessage.set(null);
        now = Instant.now();
        if (accessToken == null || !now.isBefore(tokenRefreshAt)) {
            RabbitMqOAuthClient.AccessToken token = oauthClient.requestAccessToken();
            accessToken = token.value();
            tokenRefreshAt = token.refreshAt();
        }

        ConnectionFactory factory = oauthClient.connectionFactory(accessToken);
        Connection newConnection = factory.newConnection("traffic-density-processor");
        try {
            Channel newChannel = newConnection.createChannel();
            newChannel.addReturnListener((replyCode, replyText, exchange, routingKey, properties, body) ->
                    returnedMessage.set("reply code " + replyCode + " (" + replyText
                            + "), exchange " + exchange + ", routing key " + routingKey));
            newChannel.confirmSelect();
            connection = newConnection;
            channel = newChannel;
        } catch (IOException exception) {
            try {
                newConnection.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private void closeActiveConnection() throws IOException {
        Channel activeChannel = channel;
        channel = null;
        if (activeChannel != null && activeChannel.isOpen()) {
            try {
                activeChannel.close();
            } catch (TimeoutException exception) {
                throw new IOException("Timed out while closing RabbitMQ channel", exception);
            }
        }

        Connection activeConnection = connection;
        connection = null;
        if (activeConnection != null && activeConnection.isOpen()) {
            activeConnection.close();
        }
    }

    private record Configuration(
            String exchange,
            String routingKey) {

        private static Configuration from(Map<String, String> environment) {
            Objects.requireNonNull(environment, "Environment is required");
            String exchange = required(environment, "RABBITMQ_EXCHANGE");
            String routingKey = optional(environment, "RABBITMQ_ROUTING_KEY");
            return new Configuration(exchange, routingKey);
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }

        private static String optional(Map<String, String> environment, String name) {
            String value = environment.get(name);
            return value == null ? "" : value.trim();
        }
    }
}
