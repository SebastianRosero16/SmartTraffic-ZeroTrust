package edu.smarttraffic.contracts;

import java.time.Instant;

public record TrafficUpdate(
        ZoneId zoneId,
        Instant measuredAt,
        TrafficMetrics metrics,
        TrafficStatus status) {

    public TrafficUpdate {
        if (zoneId == null) {
            throw new IllegalArgumentException("Zone ID is required");
        }
        if (measuredAt == null) {
            throw new IllegalArgumentException("Measurement time is required");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("Traffic metrics are required");
        }
        if (status == null) {
            throw new IllegalArgumentException("Traffic status is required");
        }
    }
}