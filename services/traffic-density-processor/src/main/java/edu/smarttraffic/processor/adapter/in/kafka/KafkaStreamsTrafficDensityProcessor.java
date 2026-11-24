package edu.smarttraffic.processor.adapter.in.kafka;

import edu.smarttraffic.kafkasecurity.KafkaOAuthClientConfiguration;
import edu.smarttraffic.processor.application.port.out.TrafficUpdatePublisher;
import edu.smarttraffic.processor.domain.density.AverageDensityStrategy;
import edu.smarttraffic.processor.domain.density.DensityCalculationStrategy;
import edu.smarttraffic.processor.domain.density.TimeWeightedDensityStrategy;
import edu.smarttraffic.processor.domain.event.TrafficEventObserver;
import edu.smarttraffic.processor.domain.state.TrafficThresholds;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.streams.KafkaStreams;

public final class KafkaStreamsTrafficDensityProcessor implements AutoCloseable {
    private final KafkaStreams streams;

    private KafkaStreamsTrafficDensityProcessor(KafkaStreams streams) {
        this.streams = Objects.requireNonNull(streams, "Kafka Streams instance is required");
    }

    public static KafkaStreamsTrafficDensityProcessor fromEnvironment(
            Map<String, String> environment,
            TrafficUpdatePublisher updatePublisher) {
        return fromEnvironment(environment, updatePublisher, List.of());
    }

    public static KafkaStreamsTrafficDensityProcessor fromEnvironment(
            Map<String, String> environment,
            TrafficUpdatePublisher updatePublisher,
            List<TrafficEventObserver> observers) {
        Objects.requireNonNull(environment, "Environment is required");
        Objects.requireNonNull(updatePublisher, "Traffic update publisher is required");
        Objects.requireNonNull(observers, "Traffic observers are required");

        String applicationId = required(environment, "KAFKA_STREAMS_APPLICATION_ID");
        String readingsTopic = required(environment, "KAFKA_TRAFFIC_READINGS_TOPIC");
        Duration windowSize = Duration.ofSeconds(parsePositiveLong(environment, "TRAFFIC_WINDOW_SECONDS"));
        Duration windowGrace = Duration.ofSeconds(parseNonNegativeLong(environment, "TRAFFIC_WINDOW_GRACE_SECONDS"));
        DensityCalculationStrategy densityStrategy = densityStrategyFromEnvironment(environment, windowSize);
        TrafficThresholds thresholds = new TrafficThresholds(
                parseDouble(environment, "TRAFFIC_MODERATE_THRESHOLD"),
                parseDouble(environment, "TRAFFIC_CONGESTED_THRESHOLD"));

        Properties kafkaProperties = KafkaOAuthClientConfiguration
                .fromEnvironment(environment)
                .streamsProperties(applicationId);
        TrafficDensityTopology topology = new TrafficDensityTopology(
                readingsTopic,
                windowSize,
                windowGrace,
                thresholds,
                updatePublisher,
                densityStrategy,
                observers);
        return new KafkaStreamsTrafficDensityProcessor(new KafkaStreams(topology.build(), kafkaProperties));
    }

    static DensityCalculationStrategy densityStrategyFromEnvironment(
            Map<String, String> environment,
            Duration windowSize) {
        Objects.requireNonNull(environment, "Environment is required");
        Objects.requireNonNull(windowSize, "Window size is required");
        String configuredStrategy = environment.getOrDefault("TRAFFIC_DENSITY_STRATEGY", "average");
        if (configuredStrategy == null || configuredStrategy.isBlank()) {
            throw new IllegalArgumentException("TRAFFIC_DENSITY_STRATEGY must not be blank");
        }
        return switch (configuredStrategy.trim().toLowerCase(Locale.ROOT)) {
            case "average" -> new AverageDensityStrategy();
            case "time-weighted" -> new TimeWeightedDensityStrategy(windowSize);
            default -> throw new IllegalArgumentException(
                    "TRAFFIC_DENSITY_STRATEGY must be 'average' or 'time-weighted'");
        };
    }

    public void start() {
        streams.start();
    }

    @Override
    public void close() {
        if (!streams.close(Duration.ofSeconds(30))) {
            throw new IllegalStateException("Kafka Streams did not close within 30 seconds");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static long parsePositiveLong(Map<String, String> environment, String name) {
        try {
            long value = Long.parseLong(required(environment, name));
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer", exception);
        }
    }

    private static double parseDouble(Map<String, String> environment, String name) {
        try {
            return Double.parseDouble(required(environment, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number", exception);
        }
    }

    private static long parseNonNegativeLong(Map<String, String> environment, String name) {
        try {
            long value = Long.parseLong(required(environment, name));
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a non-negative integer", exception);
        }
    }
}
