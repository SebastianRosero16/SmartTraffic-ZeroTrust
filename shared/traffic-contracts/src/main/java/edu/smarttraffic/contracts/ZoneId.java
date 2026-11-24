package edu.smarttraffic.contracts;

public record ZoneId(String value) {
    public ZoneId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Zone ID must not be blank");
        }
    }
}