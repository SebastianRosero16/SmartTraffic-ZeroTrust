package edu.smarttraffic.dashboard.config;

import java.util.Map;
import java.util.Objects;

public record DashboardSettings(String queue) {
    public static final String DEFAULT_QUEUE = "dashboard_updates_queue";

    public DashboardSettings {
        if (queue == null || queue.isBlank()) {
            throw new IllegalArgumentException("Dashboard RabbitMQ queue is required");
        }
    }

    public static DashboardSettings from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "Environment is required");
        String queue = environment.get("RABBITMQ_DASHBOARD_QUEUE");
        if (queue == null) {
            queue = DEFAULT_QUEUE;
        } else if (queue.isBlank()) {
            throw new IllegalArgumentException("RABBITMQ_DASHBOARD_QUEUE must not be blank");
        }
        return new DashboardSettings(queue.trim());
    }
}
