package edu.smarttraffic.queryclient.adapter.out.rabbitmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import edu.smarttraffic.alerts.adapter.in.rabbitmq.RabbitMqAlertDispatcher;
import edu.smarttraffic.alerts.application.TrafficAlert;
import edu.smarttraffic.alerts.application.TrafficAlertPublisher;
import edu.smarttraffic.alerts.application.TrafficStateStore;
import edu.smarttraffic.alerts.config.AlertDispatcherSettings;
import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficQueryResponse;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.queryclient.config.QueryClientSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "RABBITMQ_IT", matches = "true")
class RabbitMqRoundTripIntegrationTest {
    private static final String RESOURCE_SERVER_ID = "smarttraffic-rabbitmq-it";
    private static final String VHOST = "smarttraffic-it";
    private static final String QUERY_QUEUE = "query-requests-it";
    private static final String REPLY_QUEUE = "query-replies-it";
    private static final String QUERY_EXCHANGE = "query-answers-it";
    private static final String INSTANCE_ID = "client-it";
    private static final String QUERY_SCOPE = String.join(" ",
            RESOURCE_SERVER_ID + ".write:" + VHOST + "/" + QUERY_QUEUE,
            RESOURCE_SERVER_ID + ".write:" + VHOST + "/amq.default",
            RESOURCE_SERVER_ID + ".configure:" + VHOST + "/" + REPLY_QUEUE,
            RESOURCE_SERVER_ID + ".write:" + VHOST + "/" + REPLY_QUEUE,
            RESOURCE_SERVER_ID + ".read:" + VHOST + "/" + REPLY_QUEUE,
            RESOURCE_SERVER_ID + ".read:" + VHOST + "/" + QUERY_EXCHANGE,
            RESOURCE_SERVER_ID + ".read:" + VHOST + "/" + QUERY_EXCHANGE + "/" + INSTANCE_ID);
    private static final String ALERT_SCOPE = String.join(" ",
            RESOURCE_SERVER_ID + ".read:" + VHOST + "/alert-updates-it",
            RESOURCE_SERVER_ID + ".read:" + VHOST + "/" + QUERY_QUEUE,
            RESOURCE_SERVER_ID + ".write:" + VHOST + "/" + QUERY_EXCHANGE + "/" + INSTANCE_ID);
    private static final String PUBLISH_SCOPE =
            RESOURCE_SERVER_ID + ".write:" + VHOST + "/traffic-updates-it";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void createsExclusiveReplyQueueAndReturnsCorrelatedPopulatedTrafficState() throws Exception {
        ZoneId zoneId = new ZoneId("zone-it-" + java.util.UUID.randomUUID());
        TrafficUpdate update = new TrafficUpdate(
                zoneId,
                Instant.now(),
                new TrafficMetrics(17, 8.5),
                TrafficStatus.MODERADA);
        publishUpdate(update);

        TrafficStateStore stateStore = new TrafficStateStore();
        LinkedBlockingQueue<TrafficAlert> alerts = new LinkedBlockingQueue<>();
        TrafficAlertPublisher alertPublisher = new TrafficAlertPublisher();
        alertPublisher.subscribe(alerts::offer);
        RabbitMqAlertDispatcher dispatcher = new RabbitMqAlertDispatcher(
                oauthClient(
                        "rabbit-alert-it",
                        "RABBITMQ_IT_ALERT_SECRET",
                        ALERT_SCOPE),
                new AlertDispatcherSettings(
                        "alert-updates-it",
                        QUERY_QUEUE,
                        QUERY_EXCHANGE,
                        Map.of(INSTANCE_ID, REPLY_QUEUE)),
                stateStore,
                alertPublisher,
                OBJECT_MAPPER);
        Thread dispatcherThread = new Thread(dispatcher::run, "rabbitmq-round-trip-alert-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        try {
            awaitState(stateStore, zoneId, Duration.ofSeconds(15));
            assertEquals(new TrafficAlert(
                    zoneId,
                    TrafficStatus.DESPEJADA,
                    TrafficStatus.MODERADA,
                    update.measuredAt()), awaitAlert(alerts, zoneId, Duration.ofSeconds(5)));

            RabbitMqOAuthClient queryOauth = oauthClient(
                    "rabbit-query-client-it",
                    "RABBITMQ_IT_QUERY_SECRET",
                    QUERY_SCOPE);
            RabbitMqTrafficQueryClient queryClient = new RabbitMqTrafficQueryClient(
                    queryOauth,
                    new QueryClientSettings(
                            QUERY_QUEUE,
                            QUERY_EXCHANGE,
                            REPLY_QUEUE,
                            INSTANCE_ID,
                            List.of(zoneId),
                            10_000,
                            5_000),
                    OBJECT_MAPPER);
            try (Connection connection = connect(queryOauth, "rabbitmq-round-trip-query-client");
                 Channel queryPublisher = connection.createChannel();
                 Channel responseConsumer = connection.createChannel()) {
                queryPublisher.confirmSelect();
                queryClient.declareReplyQueue(responseConsumer);
                queryClient.startReplyConsumer(responseConsumer);
                assertExclusiveQueueCannotBeDeclaredFromAnotherConnection(queryOauth);

                TrafficQueryResponse response = queryClient.sendQuery(queryPublisher).orElseThrow(
                        () -> new AssertionError("No RabbitMQ query response was received"));

                assertFalse(response.requestId().isBlank());
                assertEquals(1, response.updates().size());
                assertEquals(update, response.updates().get(0));
            }

            assertReplyQueueWasAutoDeleted(queryOauth);
        } finally {
            dispatcher.close();
            dispatcherThread.interrupt();
            dispatcherThread.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(dispatcherThread.isAlive(), "Alert dispatcher thread did not stop");
        }
    }

    private static void publishUpdate(TrafficUpdate update) throws Exception {
        RabbitMqOAuthClient publisherOauth = oauthClient(
                "rabbit-publisher-it",
                "RABBITMQ_IT_PUBLISHER_SECRET",
                PUBLISH_SCOPE);
        try (Connection connection = connect(publisherOauth, "rabbitmq-round-trip-publisher");
             Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            channel.basicPublish(
                    "traffic-updates-it",
                    "",
                    new AMQP.BasicProperties.Builder().contentType("application/json").build(),
                    OBJECT_MAPPER.writeValueAsBytes(update));
            channel.waitForConfirmsOrDie(5_000);
        }
    }

    private static void assertExclusiveQueueCannotBeDeclaredFromAnotherConnection(
            RabbitMqOAuthClient oauthClient) throws Exception {
        try (Connection competingConnection = connect(oauthClient, "rabbitmq-round-trip-competing-client")) {
            Channel competingChannel = competingConnection.createChannel();
            IOException failure = assertThrows(IOException.class, () -> competingChannel.queueDeclare(
                    REPLY_QUEUE,
                    false,
                    true,
                    true,
                    Map.of()));
            assertInstanceOf(ShutdownSignalException.class, failure.getCause());
            ShutdownSignalException shutdown = (ShutdownSignalException) failure.getCause();
            assertInstanceOf(AMQP.Channel.Close.class, shutdown.getReason());
            assertEquals(405, ((AMQP.Channel.Close) shutdown.getReason()).getReplyCode());
        }
    }

    private static void assertReplyQueueWasAutoDeleted(RabbitMqOAuthClient oauthClient) throws Exception {
        try (Connection connection = connect(oauthClient, "rabbitmq-round-trip-cleanup-check")) {
            Channel channel = connection.createChannel();
            IOException failure = assertThrows(
                    IOException.class,
                    () -> channel.queueDeclarePassive(REPLY_QUEUE));
            assertInstanceOf(ShutdownSignalException.class, failure.getCause());
            ShutdownSignalException shutdown = (ShutdownSignalException) failure.getCause();
            assertInstanceOf(AMQP.Channel.Close.class, shutdown.getReason());
            assertEquals(404, ((AMQP.Channel.Close) shutdown.getReason()).getReplyCode());
        }
    }

    private static Connection connect(RabbitMqOAuthClient oauthClient, String connectionName)
            throws Exception {
        RabbitMqOAuthClient.AccessToken token = oauthClient.requestAccessToken();
        return oauthClient.connectionFactory(token.value()).newConnection(connectionName);
    }

    private static RabbitMqOAuthClient oauthClient(String clientId, String secretName, String scope) {
        String authBaseUrl = environmentOrDefault(
                "RABBITMQ_IT_AUTH_BASE_URL",
                "https://host.docker.internal:8443").replaceAll("/+$", "");
        String caCertificate = requiredEnvironment("RABBITMQ_IT_CA_CERT");
        return new RabbitMqOAuthClient(Map.of(
                "RABBITMQ_HOST", environmentOrDefault("RABBITMQ_IT_HOST", "rabbitmq"),
                "RABBITMQ_PORT", environmentOrDefault("RABBITMQ_IT_PORT", "5671"),
                "RABBITMQ_VHOST", VHOST,
                "AUTH_TOKEN_ENDPOINT_URI", authBaseUrl + "/oauth2/token",
                "RABBITMQ_OAUTH_CLIENT_ID", clientId,
                "RABBITMQ_OAUTH_CLIENT_SECRET", requiredEnvironment(secretName),
                "RABBITMQ_OAUTH_SCOPE", scope,
                "AUTH_TLS_CA_CERT_PATH", caCertificate,
                "RABBITMQ_TLS_CA_CERT_PATH", caCertificate));
    }

    private static void awaitState(TrafficStateStore store, ZoneId zoneId, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (store.findLatest(zoneId).isPresent()) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(store.findLatest(zoneId).isPresent(), "Dispatcher did not consume the traffic update");
    }

    private static TrafficAlert awaitAlert(
            LinkedBlockingQueue<TrafficAlert> alerts,
            ZoneId zoneId,
            Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            TrafficAlert alert = alerts.poll(100, TimeUnit.MILLISECONDS);
            if (alert != null && alert.zoneId().equals(zoneId)) {
                return alert;
            }
        }
        throw new AssertionError("No Observer alert was emitted for " + zoneId.value());
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required RabbitMQ integration-test variable is missing: " + name);
        }
        return value;
    }
}
