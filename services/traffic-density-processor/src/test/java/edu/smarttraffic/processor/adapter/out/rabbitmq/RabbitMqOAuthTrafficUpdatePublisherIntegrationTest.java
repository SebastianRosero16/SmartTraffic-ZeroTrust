package edu.smarttraffic.processor.adapter.out.rabbitmq;

import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "RABBITMQ_IT", matches = "true")
class RabbitMqOAuthTrafficUpdatePublisherIntegrationTest {
    @Test
    void rejectsUnroutableTrafficUpdate() throws Exception {
        String caCertificate = requiredEnvironment("RABBITMQ_IT_CA_CERT");
        Map<String, String> environment = Map.ofEntries(
                Map.entry("RABBITMQ_HOST", "rabbitmq"),
                Map.entry("RABBITMQ_PORT", "5671"),
                Map.entry("RABBITMQ_VHOST", "smarttraffic-it"),
                Map.entry("RABBITMQ_EXCHANGE", "query-answers-it"),
                Map.entry("RABBITMQ_ROUTING_KEY", "unmatched-it"),
                Map.entry("AUTH_TOKEN_ENDPOINT_URI", "https://host.docker.internal:8443/oauth2/token"),
                Map.entry("RABBITMQ_OAUTH_CLIENT_ID", "rabbit-publisher-it"),
                Map.entry("RABBITMQ_OAUTH_CLIENT_SECRET",
                        requiredEnvironment("RABBITMQ_IT_PUBLISHER_SECRET")),
                Map.entry(
                        "RABBITMQ_OAUTH_SCOPE",
                        "smarttraffic-rabbitmq-it.write:smarttraffic-it/query-answers-it/unmatched-it"),
                Map.entry("AUTH_TLS_CA_CERT_PATH", caCertificate),
                Map.entry("RABBITMQ_TLS_CA_CERT_PATH", caCertificate));
        TrafficUpdate update = new TrafficUpdate(
                new ZoneId("publisher-adapter-it"),
                Instant.parse("2026-11-14T18:00:00Z"),
                new TrafficMetrics(12, 6.5),
                TrafficStatus.MODERADA);

        try (var publisher = new RabbitMqOAuthTrafficUpdatePublisher(environment)) {
            IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> publisher.publish(update));
            org.junit.jupiter.api.Assertions.assertTrue(
                    String.valueOf(failure.getMessage()).contains("NO_ROUTE"));
        }
    }

    @BeforeAll
    static void requireIntegrationCredentials() {
        requiredEnvironment("RABBITMQ_IT_PUBLISHER_SECRET");
        requiredEnvironment("RABBITMQ_IT_CA_CERT");
    }

    @Test
    void publishesTrafficUpdateUsingOauthAndPublisherConfirms() throws Exception {
        String caCertificate = requiredEnvironment("RABBITMQ_IT_CA_CERT");
        Map<String, String> environment = Map.ofEntries(
                Map.entry("RABBITMQ_HOST", "rabbitmq"),
                Map.entry("RABBITMQ_PORT", "5671"),
                Map.entry("RABBITMQ_VHOST", "smarttraffic-it"),
                Map.entry("RABBITMQ_EXCHANGE", "traffic-updates-it"),
                Map.entry("RABBITMQ_ROUTING_KEY", ""),
                Map.entry("AUTH_TOKEN_ENDPOINT_URI", "https://host.docker.internal:8443/oauth2/token"),
                Map.entry("RABBITMQ_OAUTH_CLIENT_ID", "rabbit-publisher-it"),
                Map.entry("RABBITMQ_OAUTH_CLIENT_SECRET",
                        requiredEnvironment("RABBITMQ_IT_PUBLISHER_SECRET")),
                Map.entry(
                        "RABBITMQ_OAUTH_SCOPE",
                        "smarttraffic-rabbitmq-it.write:smarttraffic-it/traffic-updates-it"),
                Map.entry("AUTH_TLS_CA_CERT_PATH", caCertificate),
                Map.entry("RABBITMQ_TLS_CA_CERT_PATH", caCertificate));
        TrafficUpdate update = new TrafficUpdate(
                new ZoneId("publisher-adapter-it"),
                Instant.parse("2026-11-14T18:00:00Z"),
                new TrafficMetrics(12, 6.5),
                TrafficStatus.MODERADA);

        try (var publisher = new RabbitMqOAuthTrafficUpdatePublisher(environment)) {
            publisher.publish(update);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required integration-test variable is missing: " + name);
        }
        return value;
    }
}
