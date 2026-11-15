package edu.smarttraffic.processor.adapter.in.kafka;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KafkaStreamsTrafficDensityProcessorTest {
    @Test
    void rejectsMissingWindowAndThresholdConfiguration() {
        Map<String, String> environment = new HashMap<>(kafkaEnvironment());
        environment.put("KAFKA_STREAMS_APPLICATION_ID", "traffic-density-processor");
        environment.put("KAFKA_TRAFFIC_READINGS_TOPIC", "traffic_readings");
        environment.put("TRAFFIC_WINDOW_SECONDS", "60");
        environment.put("TRAFFIC_WINDOW_GRACE_SECONDS", "0");

        assertThrows(IllegalArgumentException.class,
                () -> KafkaStreamsTrafficDensityProcessor.fromEnvironment(environment, update -> {
                }));

        environment.put("TRAFFIC_MODERATE_THRESHOLD", "5");
        environment.put("TRAFFIC_CONGESTED_THRESHOLD", "5");
        assertThrows(IllegalArgumentException.class,
                () -> KafkaStreamsTrafficDensityProcessor.fromEnvironment(environment, update -> {
                }));
    }

    private static Map<String, String> kafkaEnvironment() {
        return Map.of(
                "KAFKA_BOOTSTRAP_SERVERS", "localhost:9093",
                "AUTH_TOKEN_ENDPOINT_URI", "https://auth.example.test/oauth2/token",
                "KAFKA_OAUTH_CLIENT_ID", "traffic-processor",
                "KAFKA_OAUTH_CLIENT_SECRET", "test-secret");
    }
}
