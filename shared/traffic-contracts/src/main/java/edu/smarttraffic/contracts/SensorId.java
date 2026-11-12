package edu.smarttraffic.contracts;

public record SensorId(String value) {
    public SensorId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sensor ID must not be blank");
        }
    }
}