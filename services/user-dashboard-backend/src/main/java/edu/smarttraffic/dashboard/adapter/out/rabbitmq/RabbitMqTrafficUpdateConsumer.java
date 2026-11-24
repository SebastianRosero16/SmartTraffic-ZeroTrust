package edu.smarttraffic.dashboard.adapter.out.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class RabbitMqTrafficUpdateConsumer implements AutoCloseable {
    private static final System.Logger LOGGER =
            System.getLogger(RabbitMqTrafficUpdateConsumer.class.getName());
    private static final long INITIAL_RETRY_DELAY_MILLIS = 1_000;
    private static final long MAX_RETRY_DELAY_MILLIS = 30_000;
    private static final long TOKEN_REFRESH_CHECK_MILLIS = 500;

    private final RabbitMqOAuthClient oauthClient;
    private final String queue;
    private final Consumer<TrafficUpdate> updateHandler;
    private final ObjectMapper objectMapper;

    private volatile boolean running;
    private volatile Thread worker;
    private volatile Connection connection;
    private volatile Channel channel;

    public RabbitMqTrafficUpdateConsumer(
            RabbitMqOAuthClient oauthClient,
            String queue,
            Consumer<TrafficUpdate> updateHandler,
            ObjectMapper objectMapper) {
        this.oauthClient = Objects.requireNonNull(oauthClient, "RabbitMQ OAuth client is required");
        if (queue == null || queue.isBlank()) {
            throw new IllegalArgumentException("Dashboard RabbitMQ queue is required");
        }
        this.queue = queue.trim();
        this.updateHandler = Objects.requireNonNull(updateHandler, "Traffic update handler is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper is required");
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::consume, "dashboard-rabbitmq-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void close() {
        running = false;
        Thread activeWorker = worker;
        if (activeWorker != null) {
            activeWorker.interrupt();
        }
        closeActiveConnection();
        if (activeWorker != null && activeWorker != Thread.currentThread()) {
            try {
                activeWorker.join(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.log(System.Logger.Level.WARNING, "Interrupted while stopping dashboard consumer", exception);
            }
        }
    }

    private void consume() {
        long retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS;
        while (running) {
            Connection activeConnection = null;
            Channel activeChannel = null;
            boolean connected = false;
            try {
                RabbitMqOAuthClient.AccessToken token = oauthClient.requestAccessToken();
                activeConnection = oauthClient.connectionFactory(token.value())
                        .newConnection("user-dashboard-backend");
                Channel consumerChannel = activeConnection.createChannel();
                activeChannel = consumerChannel;
                connection = activeConnection;
                channel = consumerChannel;
                consumerChannel.basicQos(1);
                consumerChannel.basicConsume(queue, false, new DefaultConsumer(consumerChannel) {
                    @Override
                    public void handleDelivery(
                            String consumerTag,
                            Envelope envelope,
                            AMQP.BasicProperties properties,
                            byte[] body) throws IOException {
                        processDelivery(consumerChannel, envelope, body);
                    }
                });
                connected = true;
                retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS;

                while (running
                        && activeConnection.isOpen()
                        && consumerChannel.isOpen()
                        && Instant.now().isBefore(token.refreshAt())) {
                    Thread.sleep(TOKEN_REFRESH_CHECK_MILLIS);
                }
            } catch (InterruptedException exception) {
                if (running) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Dashboard RabbitMQ consumer was interrupted unexpectedly",
                            exception);
                }
                break;
            } catch (IOException | TimeoutException | ShutdownSignalException exception) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Dashboard RabbitMQ consumer disconnected; it will retry",
                        exception);
            } finally {
                closeConnection(activeChannel, activeConnection);
            }

            if (running) {
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException exception) {
                    if (running) {
                        Thread.currentThread().interrupt();
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Dashboard RabbitMQ retry was interrupted unexpectedly",
                                exception);
                    }
                    break;
                }
                if (!connected) {
                    retryDelayMillis = Math.min(retryDelayMillis * 2, MAX_RETRY_DELAY_MILLIS);
                }
            }
        }
    }

    void processDelivery(Channel activeChannel, Envelope envelope, byte[] body) throws IOException {
        TrafficUpdate update;
        try {
            update = objectMapper.readValue(body, TrafficUpdate.class);
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Rejecting invalid traffic update message", exception);
            activeChannel.basicNack(envelope.getDeliveryTag(), false, false);
            return;
        }

        try {
            updateHandler.accept(update);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Could not apply traffic update; requeuing it", exception);
            activeChannel.basicNack(envelope.getDeliveryTag(), false, true);
            return;
        }
        activeChannel.basicAck(envelope.getDeliveryTag(), false);
    }

    private void closeActiveConnection() {
        closeConnection(channel, connection);
    }

    private void closeConnection(Channel activeChannel, Connection activeConnection) {
        if (channel == activeChannel) {
            channel = null;
        }
        if (connection == activeConnection) {
            connection = null;
        }
        if (activeChannel != null && activeChannel.isOpen()) {
            try {
                activeChannel.close();
            } catch (IOException | TimeoutException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not close dashboard RabbitMQ channel", exception);
            }
        }
        if (activeConnection != null && activeConnection.isOpen()) {
            try {
                activeConnection.close();
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not close dashboard RabbitMQ connection", exception);
            }
        }
    }
}
