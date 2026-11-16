package edu.smarttraffic.alerts.config;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record AlertDispatcherSettings(
        String updatesQueue,
        String queriesQueue,
        String answersExchange,
        Map<String, String> replyQueuesByInstance) {

    public AlertDispatcherSettings {
        updatesQueue = required(updatesQueue, "RABBITMQ_ALERT_QUEUE");
        queriesQueue = required(queriesQueue, "RABBITMQ_QUERY_QUEUE");
        answersExchange = required(answersExchange, "RABBITMQ_QUERY_ANSWERS_EXCHANGE");
        if (replyQueuesByInstance == null || replyQueuesByInstance.isEmpty()) {
            throw new IllegalArgumentException("At least one query reply route is required");
        }
        replyQueuesByInstance = Map.copyOf(replyQueuesByInstance);
    }

    public static AlertDispatcherSettings from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "Environment is required");
        return new AlertDispatcherSettings(
                environment.get("RABBITMQ_ALERT_QUEUE"),
                environment.get("RABBITMQ_QUERY_QUEUE"),
                environment.get("RABBITMQ_QUERY_ANSWERS_EXCHANGE"),
                parseReplyQueues(required(
                        environment.get("RABBITMQ_QUERY_REPLY_ROUTES"),
                        "RABBITMQ_QUERY_REPLY_ROUTES")));
    }

    public String replyQueueFor(String instanceId) {
        return replyQueuesByInstance.get(instanceId);
    }

    private static Map<String, String> parseReplyQueues(String value) {
        Map<String, String> routes = java.util.Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .map(route -> {
                    int separator = route.indexOf('=');
                    if (separator <= 0 || separator == route.length() - 1) {
                        throw new IllegalArgumentException(
                                "RABBITMQ_QUERY_REPLY_ROUTES must contain instanceId=queue entries");
                    }
                    return Map.entry(
                            route.substring(0, separator).trim(),
                            route.substring(separator + 1).trim());
                })
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, second) -> {
                            throw new IllegalArgumentException(
                                    "RABBITMQ_QUERY_REPLY_ROUTES must not repeat an instance ID");
                        }));
        if (routes.keySet().stream().anyMatch(String::isBlank)
                || routes.values().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "RABBITMQ_QUERY_REPLY_ROUTES must not contain blank instance IDs or queues");
        }
        return routes;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
