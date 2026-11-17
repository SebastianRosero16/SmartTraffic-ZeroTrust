package edu.smarttraffic.alerts.adapter.in.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import edu.smarttraffic.alerts.application.TrafficStateStore;
import edu.smarttraffic.alerts.config.AlertDispatcherSettings;
import edu.smarttraffic.contracts.TrafficQuery;
import edu.smarttraffic.contracts.TrafficQueryResponse;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class RabbitMqAlertDispatcher implements AutoCloseable {
    private static final System.Logger LOGGER =
            System.getLogger(RabbitMqAlertDispatcher.class.getName());
    private static final long INITIAL_RETRY_DELAY_MILLIS = 1_000;
    private static final long MAX_RETRY_DELAY_MILLIS = 30_000;
    private static final long TOKEN_REFRESH_CHECK_MILLIS = 500;
    private static final long PUBLISH_CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final RabbitMqOAuthClient oauthClient;
    private final AlertDispatcherSettings settings;
    private final TrafficStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final AtomicReference<String> returnedMessage = new AtomicReference<>();

    private volatile boolean running = true;
    private volatile Connection connection;
    private volatile Channel updatesChannel;
    private volatile Channel queriesChannel;

    public RabbitMqAlertDispatcher(
            RabbitMqOAuthClient oauthClient,
            AlertDispatcherSettings settings,
            TrafficStateStore stateStore,
            ObjectMapper objectMapper) {
        this.oauthClient = Objects.requireNonNull(oauthClient, "RabbitMQ OAuth client is required");
        this.settings = Objects.requireNonNull(settings, "Dispatcher settings are required");
        this.stateStore = Objects.requireNonNull(stateStore, "Traffic state store is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper is required");
    }

    public void run() {
        long retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS;
        while (running) {
            Connection activeConnection = null;
            Channel activeUpdatesChannel = null;
            Channel activeQueriesChannel = null;
            boolean connected = false;
            try {
                RabbitMqOAuthClient.AccessToken token = oauthClient.requestAccessToken();
                activeConnection = oauthClient.connectionFactory(token.value())
                        .newConnection("alert-dispatcher");
                Channel updateConsumerChannel = activeConnection.createChannel();
                Channel queryConsumerChannel = activeConnection.createChannel();
                activeUpdatesChannel = updateConsumerChannel;
                activeQueriesChannel = queryConsumerChannel;
                connection = activeConnection;
                updatesChannel = updateConsumerChannel;
                queriesChannel = queryConsumerChannel;

                updateConsumerChannel.basicQos(1);
                queryConsumerChannel.basicQos(1);
                queryConsumerChannel.confirmSelect();
                queryConsumerChannel.addReturnListener(
                        (replyCode, replyText, exchange, routingKey, properties, body) ->
                                returnedMessage.set("reply code " + replyCode + " (" + replyText
                                        + "), exchange " + exchange + ", routing key " + routingKey));
                updateConsumerChannel.basicConsume(
                        settings.updatesQueue(),
                        false,
                        new DefaultConsumer(updateConsumerChannel) {
                            @Override
                            public void handleDelivery(
                                    String consumerTag,
                                    Envelope envelope,
                                    AMQP.BasicProperties properties,
                                    byte[] body) throws IOException {
                                processUpdate(updateConsumerChannel, envelope, body);
                            }
                        });
                queryConsumerChannel.basicConsume(
                        settings.queriesQueue(),
                        false,
                        new DefaultConsumer(queryConsumerChannel) {
                            @Override
                            public void handleDelivery(
                                    String consumerTag,
                                    Envelope envelope,
                                    AMQP.BasicProperties properties,
                                    byte[] body) throws IOException {
                                processQuery(queryConsumerChannel, envelope, properties, body);
                            }
                        });
                connected = true;
                retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS;

                while (running
                        && activeConnection.isOpen()
                        && updateConsumerChannel.isOpen()
                        && queryConsumerChannel.isOpen()
                        && Instant.now().isBefore(token.refreshAt())) {
                    Thread.sleep(TOKEN_REFRESH_CHECK_MILLIS);
                }
            } catch (InterruptedException exception) {
                if (running) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Alert dispatcher was interrupted unexpectedly",
                            exception);
                }
                break;
            } catch (IOException | TimeoutException | ShutdownSignalException exception) {
                if (running) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Alert dispatcher disconnected from RabbitMQ; it will retry",
                            exception);
                }
            } finally {
                closeConnection(activeQueriesChannel, activeUpdatesChannel, activeConnection);
            }

            if (running) {
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException exception) {
                    if (running) {
                        Thread.currentThread().interrupt();
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Alert dispatcher retry was interrupted unexpectedly",
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

    @Override
    public void close() {
        running = false;
        closeConnection(queriesChannel, updatesChannel, connection);
    }

    void processUpdate(Channel channel, Envelope envelope, byte[] body) throws IOException {
        TrafficUpdate update;
        try {
            update = objectMapper.readValue(body, TrafficUpdate.class);
            if (update == null) {
                throw new IllegalArgumentException("Traffic update message must not be null");
            }
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Rejecting invalid traffic update message", exception);
            channel.basicNack(envelope.getDeliveryTag(), false, false);
            return;
        }

        try {
            TrafficStateStore.Acceptance acceptance = stateStore.accept(update);
            if (!acceptance.accepted()) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "Ignoring stale traffic update for zone " + update.zoneId().value());
            }
            acceptance.alert().ifPresent(alert -> LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Traffic alert: zone=" + alert.zoneId().value()
                            + ", status=" + alert.previousStatus() + "->" + alert.currentStatus()
                            + ", measuredAt=" + alert.measuredAt()));
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Could not apply traffic update; requeuing it", exception);
            channel.basicNack(envelope.getDeliveryTag(), false, true);
            return;
        }
        channel.basicAck(envelope.getDeliveryTag(), false);
    }

    void processQuery(
            Channel channel,
            Envelope envelope,
            AMQP.BasicProperties properties,
            byte[] body) throws IOException {
        TrafficQuery query;
        String correlationId = properties == null ? null : properties.getCorrelationId();
        String replyTo = properties == null ? null : properties.getReplyTo();
        try {
            query = objectMapper.readValue(body, TrafficQuery.class);
            if (query == null) {
                throw new IllegalArgumentException("Traffic query message must not be null");
            }
            String expectedReplyQueue = settings.replyQueueFor(query.instanceId());
            if (correlationId == null || correlationId.isBlank()
                    || replyTo == null || replyTo.isBlank()
                    || !correlationId.equals(query.requestId())
                    || !replyTo.equals(expectedReplyQueue)) {
                throw new IllegalArgumentException(
                        "Query correlationId, instanceId, and authorized replyTo must match");
            }
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Rejecting invalid traffic query message", exception);
            channel.basicNack(envelope.getDeliveryTag(), false, false);
            return;
        }

        try {
            TrafficQueryResponse response = new TrafficQueryResponse(
                    query.requestId(),
                    query.zoneIds().stream()
                            .map(stateStore::findLatest)
                            .flatMap(java.util.Optional::stream)
                            .toList());
            byte[] responseBody = objectMapper.writeValueAsBytes(response);
            AMQP.BasicProperties responseProperties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .correlationId(correlationId)
                    .build();
            returnedMessage.set(null);
            channel.basicPublish(
                    settings.answersExchange(),
                    query.instanceId(),
                    true,
                    responseProperties,
                    responseBody);
            channel.waitForConfirmsOrDie(PUBLISH_CONFIRM_TIMEOUT_MILLIS);
            String returnDetails = returnedMessage.getAndSet(null);
            if (returnDetails != null) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Discarding query with an unroutable response: " + returnDetails);
                channel.basicNack(envelope.getDeliveryTag(), false, false);
                return;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.log(System.Logger.Level.ERROR, "Interrupted while publishing traffic query response", exception);
            channel.basicNack(envelope.getDeliveryTag(), false, true);
            return;
        } catch (IOException | TimeoutException exception) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Could not publish traffic query response; requeuing query",
                    exception);
            channel.basicNack(envelope.getDeliveryTag(), false, true);
            return;
        }
        channel.basicAck(envelope.getDeliveryTag(), false);
    }

    private void closeConnection(Channel activeQueriesChannel, Channel activeUpdatesChannel, Connection activeConnection) {
        if (queriesChannel == activeQueriesChannel) {
            queriesChannel = null;
        }
        if (updatesChannel == activeUpdatesChannel) {
            updatesChannel = null;
        }
        if (connection == activeConnection) {
            connection = null;
        }
        closeChannel(activeQueriesChannel);
        closeChannel(activeUpdatesChannel);
        if (activeConnection != null && activeConnection.isOpen()) {
            try {
                activeConnection.close();
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not close alert dispatcher connection", exception);
            }
        }
    }

    private static void closeChannel(Channel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException | TimeoutException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not close alert dispatcher channel", exception);
            }
        }
    }
}
