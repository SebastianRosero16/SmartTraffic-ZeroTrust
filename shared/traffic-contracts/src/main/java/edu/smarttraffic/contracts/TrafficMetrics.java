package edu.smarttraffic.contracts;

public record TrafficMetrics(int vehicleCount, double densityValue) {
    public TrafficMetrics {
        if (vehicleCount < 0) {
            throw new IllegalArgumentException("Vehicle count must not be negative");
        }
        if (!Double.isFinite(densityValue) || densityValue < 0) {
            throw new IllegalArgumentException("Density value must be finite and non-negative");
        }
    }
}