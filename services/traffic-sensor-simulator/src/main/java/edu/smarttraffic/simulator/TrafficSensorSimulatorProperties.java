package edu.smarttraffic.simulator;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TrafficSensorSimulatorProperties(
        String topic,
        List<SensorId> sensors,
        List<ZoneId> zones,
        int minimumVehicles,
        int maximumVehicles,
        Duration interval) {

    public TrafficSensorSimulatorProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("KAFKA_TRAFFIC_READINGS_TOPIC is required");
        }
        topic = topic.trim();
        sensors = List.copyOf(Objects.requireNonNull(sensors, "Sensors are required"));
        zones = List.copyOf(Objects.requireNonNull(zones, "Zones are required"));
        if (sensors.isEmpty() || zones.isEmpty()) {
            throw new IllegalArgumentException("At least one sensor and one zone are required");
        }
        if (minimumVehicles < 0 || maximumVehicles < minimumVehicles) {
            throw new IllegalArgumentException("Vehicle limits must be non-negative and ordered");
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Traffic interval must be positive");
        }
    }

    public static TrafficSensorSimulatorProperties fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "Environment is required");
        return new TrafficSensorSimulatorProperties(
                required(environment, "KAFKA_TRAFFIC_READINGS_TOPIC"),
                parseIdentifiers(required(environment, "TRAFFIC_SENSOR_IDS"), SensorId::new),
                parseIdentifiers(required(environment, "TRAFFIC_ZONE_IDS"), ZoneId::new),
                parseInteger(environment, "TRAFFIC_MIN_VEHICLES"),
                parseInteger(environment, "TRAFFIC_MAX_VEHICLES"),
                Duration.ofMillis(parsePositiveLong(environment, "TRAFFIC_INTERVAL_MS")));
    }

    private static <T> List<T> parseIdentifiers(String value, java.util.function.Function<String, T> factory) {
        return Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .map(factory)
                .toList();
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static int parseInteger(Map<String, String> environment, String name) {
        try {
            return Integer.parseInt(required(environment, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
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
}
