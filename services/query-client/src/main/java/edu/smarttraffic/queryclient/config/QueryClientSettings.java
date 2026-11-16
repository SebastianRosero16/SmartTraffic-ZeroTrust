package edu.smarttraffic.queryclient.config;

import edu.smarttraffic.contracts.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record QueryClientSettings(
        String requestsQueue,
        String replyQueue,
        String instanceId,
        List<ZoneId> zoneIds,
        long intervalMillis,
        long responseTimeoutMillis) {

    public QueryClientSettings {
        requestsQueue = required(requestsQueue, "RABBITMQ_QUERY_QUEUE");
        replyQueue = required(replyQueue, "RABBITMQ_QUERY_REPLY_QUEUE");
        instanceId = required(instanceId, "RABBITMQ_QUERY_CLIENT_ID");
        if (zoneIds == null || zoneIds.isEmpty()) {
            throw new IllegalArgumentException("At least one query zone is required");
        }
        zoneIds = List.copyOf(zoneIds);
        if (intervalMillis < 1_000) {
            throw new IllegalArgumentException("QUERY_INTERVAL_SECONDS must be at least 1");
        }
        if (responseTimeoutMillis < 1_000) {
            throw new IllegalArgumentException("QUERY_TIMEOUT_SECONDS must be at least 1");
        }
    }

    public static QueryClientSettings from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "Environment is required");
        return new QueryClientSettings(
                environment.get("RABBITMQ_QUERY_QUEUE"),
                environment.get("RABBITMQ_QUERY_REPLY_QUEUE"),
                environment.get("RABBITMQ_QUERY_CLIENT_ID"),
                parseZones(required(environment, "QUERY_ZONE_IDS")),
                seconds(environment, "QUERY_INTERVAL_SECONDS", 10),
                seconds(environment, "QUERY_TIMEOUT_SECONDS", 10));
    }

    private static List<ZoneId> parseZones(String value) {
        List<ZoneId> zones = Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .map(ZoneId::new)
                .toList();
        if (zones.stream().map(ZoneId::value).distinct().count() != zones.size()) {
            throw new IllegalArgumentException("QUERY_ZONE_IDS must not contain duplicates");
        }
        return zones;
    }

    private static long seconds(Map<String, String> environment, String name, long defaultValue) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return Math.multiplyExact(defaultValue, 1_000);
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 1 || seconds > 86_400) {
                throw new IllegalArgumentException(name + " must be between 1 and 86400 seconds");
            }
            return seconds * 1_000;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer number of seconds", exception);
        }
    }

    private static String required(Map<String, String> environment, String name) {
        return required(environment.get(name), name);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
