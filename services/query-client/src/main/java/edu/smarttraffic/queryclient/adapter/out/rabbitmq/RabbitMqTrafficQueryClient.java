package edu.smarttraffic.queryclient.adapter.out.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import edu.smarttraffic.contracts.TrafficQuery;
import edu.smarttraffic.contracts.TrafficQueryResponse;
import edu.smarttraffic.queryclient.config.QueryClientSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class RabbitMqTrafficQueryClient implements AutoCloseable {
    private static final System.Logger LOGGER =
            System.getLogger(RabbitMqTrafficQueryClient.class.getName());
    private static final long INITIAL_RETRY_DELAY_MILLIS = 1_000;
    private static final long MAX_RETRY_DELAY_MILLIS = 30_000;
    private static final long TOKEN_REFRESH_CHECK_MILLIS = 500;
    private static final long PUBLISH_CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final RabbitMqOAuthClient oauthClient;
    private final QueryClientSettings settings;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CompletableFuture<TrafficQueryResponse>> pending =
            new ConcurrentHashMap<>();
    private final AtomicReference<String> returnedMessage = new AtomicReference<>();

    private volatile boolean running = true;
    private volatile Connection connection;
    private volatile Channel publisherChannel;
    private volatile Channel replyChannel;

    public RabbitMqTrafficQueryClient(
            RabbitMqOAuthClient oauthClient,
            QueryClientSettings settings,
            ObjectMapper objectMapper) {
        this.oauthClient = Objects.requireNonNull(oauthClient, "RabbitMQ OAuth client is required");
        this.settings = Objects.requireNonNull(settings, "Query client settings are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper is required");
    }

    public void run() {
        long retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS;
        while (running) {
            Connection activeConnection = null;
            Channel activePublisherChannel = null;
            Channel activeReplyChannel = null;
            boolean connected = false;
            try {
                RabbitMqOAuthClient.AccessToken token = oauthClient.requestAccessToken();
                activeConnection = oauthClient.connectionFactory(token.value())
                        .newConnection("query-client-" + settings.instanceId());
                Channel requestPublisher = activeConnection.createChannel();
                Channel responseConsumer = activeConnection.createChannel();
                activePublisherChannel = requestPublisher;
                activeReplyChannel = responseConsumer;
                connection = activeConnection;
                publisherChannel = requestPublisher;
                replyChannel = responseConsumer;

                requestPublisher.confirmSelect();
                responseConsumer.basicQos(1);
                requestPublisher.addReturnListener(
                        (replyCode, replyText, exchange, routingKey, properties, body) ->
                                returnedMessage.set("reply code " + replyCode + " (" + replyText
                                        + "), exchange " + exchange + ", routing key " + routingKey));
                responseConsumer.basicConsume(
                        settings.replyQueue(),
                        false,
                        new DefaultConsumer(responseConsumer) {
                            @Override
                            public void handleDelivery(
                                    String consumerTag,
                                    Envelope envelope,
                                    AMQP.BasicProperties properties,
                                    byte[] body) throws IOException {
                                processResponse(responseConsumer, envelope, properties, body);
                            }
                        });
                connected = true;
                retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS;

                while (running
                        && activeConnection.isOpen()
                        && requestPublisher.isOpen()
                        && responseConsumer.isOpen()
                        && java.time.Instant.now().isBefore(token.refreshAt())) {
                    sendQuery(requestPublisher);
                    sleepUntilNextQuery(token.refreshAt());
                }
            } catch (InterruptedException exception) {
                if (running) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Query client was interrupted unexpectedly",
                            exception);
                }
                break;
            } catch (IOException | TimeoutException | ShutdownSignalException exception) {
                if (running) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Query client disconnected from RabbitMQ; it will retry",
                            exception);
                }
            } finally {
                completePendingExceptionally(new IOException("RabbitMQ connection was closed"));
                closeConnection(activeReplyChannel, activePublisherChannel, activeConnection);
            }

            if (running) {
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException exception) {
                    if (running) {
                        Thread.currentThread().interrupt();
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Query client retry was interrupted unexpectedly",
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
        completePendingExceptionally(new IOException("Query client is shutting down"));
        closeConnection(replyChannel, publisherChannel, connection);
    }

    void processResponse(
            Channel channel,
            Envelope envelope,
            AMQP.BasicProperties properties,
            byte[] body) throws IOException {
        TrafficQueryResponse response;
        String correlationId;
        try {
            response = objectMapper.readValue(body, TrafficQueryResponse.class);
            if (response == null) {
                throw new IllegalArgumentException("Traffic query response must not be null");
            }
            correlationId = properties == null ? null : properties.getCorrelationId();
            if (correlationId == null || correlationId.isBlank()
                    || !correlationId.equals(response.requestId())) {
                throw new IllegalArgumentException("Response correlationId does not match requestId");
            }
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Rejecting invalid traffic query response", exception);
            channel.basicNack(envelope.getDeliveryTag(), false, false);
            return;
        }

        CompletableFuture<TrafficQueryResponse> request = pending.get(correlationId);
        if (request != null) {
            request.complete(response);
        } else {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Discarding unmatched traffic query response " + correlationId);
        }
        channel.basicAck(envelope.getDeliveryTag(), false);
    }

    private void sendQuery(Channel channel) throws IOException, InterruptedException, TimeoutException {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<TrafficQueryResponse> responseFuture = registerPending(requestId);
        TrafficQuery query = new TrafficQuery(requestId, settings.instanceId(), settings.zoneIds());
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2)
                .correlationId(requestId)
                .replyTo(settings.replyQueue())
                .build();

        try {
            returnedMessage.set(null);
            channel.basicPublish(
                    settings.requestsExchange(),
                    settings.requestsQueue(),
                    true,
                    properties,
                    objectMapper.writeValueAsBytes(query));
            channel.waitForConfirmsOrDie(PUBLISH_CONFIRM_TIMEOUT_MILLIS);
            String returnDetails = returnedMessage.getAndSet(null);
            if (returnDetails != null) {
                throw new IOException("RabbitMQ returned traffic query as unroutable: " + returnDetails);
            }

            try {
                TrafficQueryResponse response =
                        responseFuture.get(settings.responseTimeoutMillis(), TimeUnit.MILLISECONDS);
                LOGGER.log(System.Logger.Level.INFO,
                        "Received query response " + requestId + " for "
                                + response.updates().size() + " zone(s)");
                java.util.Set<String> returnedZones = response.updates().stream()
                        .map(update -> update.zoneId().value())
                        .collect(java.util.stream.Collectors.toSet());
                response.updates().forEach(update -> LOGGER.log(
                        System.Logger.Level.INFO,
                        "Traffic state: zone=" + update.zoneId().value()
                                + ", status=" + update.status()
                                + ", measuredAt=" + update.measuredAt()));
                settings.zoneIds().stream()
                        .map(zoneId -> zoneId.value())
                        .filter(zoneId -> !returnedZones.contains(zoneId))
                        .forEach(zoneId -> LOGGER.log(
                                System.Logger.Level.INFO,
                                "No traffic state available for zone " + zoneId));
            } catch (TimeoutException exception) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "No response received for traffic query " + requestId
                                + " within " + settings.responseTimeoutMillis() + " ms");
            } catch (ExecutionException exception) {
                throw new IOException("Could not receive traffic query response", exception.getCause());
            }
        } finally {
            pending.remove(requestId, responseFuture);
        }
    }

    CompletableFuture<TrafficQueryResponse> registerPending(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID is required");
        }
        CompletableFuture<TrafficQueryResponse> response = new CompletableFuture<>();
        if (pending.putIfAbsent(requestId, response) != null) {
            throw new IllegalStateException("A traffic query with this request ID is already pending");
        }
        return response;
    }

    private void sleepUntilNextQuery(java.time.Instant tokenRefreshAt) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.intervalMillis());
        while (running && java.time.Instant.now().isBefore(tokenRefreshAt)) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return;
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            Thread.sleep(Math.min(TOKEN_REFRESH_CHECK_MILLIS, Math.max(1, remainingMillis)));
        }
    }

    private void completePendingExceptionally(IOException exception) {
        pending.forEach((requestId, future) -> future.completeExceptionally(exception));
        pending.clear();
    }

    private void closeConnection(Channel activeReplyChannel, Channel activePublisherChannel, Connection activeConnection) {
        if (replyChannel == activeReplyChannel) {
            replyChannel = null;
        }
        if (publisherChannel == activePublisherChannel) {
            publisherChannel = null;
        }
        if (connection == activeConnection) {
            connection = null;
        }
        closeChannel(activeReplyChannel);
        closeChannel(activePublisherChannel);
        if (activeConnection != null && activeConnection.isOpen()) {
            try {
                activeConnection.close();
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not close query client connection", exception);
            }
        }
    }

    private static void closeChannel(Channel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException | TimeoutException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not close query client channel", exception);
            }
        }
    }
}
