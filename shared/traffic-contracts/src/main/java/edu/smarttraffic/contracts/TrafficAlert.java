package edu.smarttraffic.contracts;

import java.time.Instant;

public record TrafficAlert(
        String alertId,
        ZoneId zoneId,
        Instant detectedAt,
        TrafficStatus previousStatus,
        TrafficStatus currentStatus,
        String message) {

    public TrafficAlert {
        if (alertId == null || alertId.isBlank()) {
            throw new IllegalArgumentException("Alert ID must not be blank");
        }
        if (zoneId == null) {
            throw new IllegalArgumentException("Zone ID is required");
        }
        if (detectedAt == null) {
            throw new IllegalArgumentException("Detection time is required");
        }
        if (previousStatus == null || currentStatus == null) {
            throw new IllegalArgumentException("Previous and current statuses are required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Alert message must not be blank");
        }
    }
}