package edu.smarttraffic.contracts;

import java.time.Instant;

public record TrafficReading(
        String eventId,
        SensorId sensorId,
        ZoneId zoneId,
        Instant occurredAt,
        int vehicleCount) {

    public TrafficReading {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Event ID must not be blank");
        }
        if (sensorId == null) {
            throw new IllegalArgumentException("Sensor ID is required");
        }
        if (zoneId == null) {
            throw new IllegalArgumentException("Zone ID is required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("Occurrence time is required");
        }
        if (vehicleCount < 0) {
            throw new IllegalArgumentException("Vehicle count must not be negative");
        }
    }
}