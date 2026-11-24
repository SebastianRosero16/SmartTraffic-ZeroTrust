package edu.smarttraffic.processor.domain.state;

final class TrafficStateSupport {
    private TrafficStateSupport() {
    }

    static void validateTransition(double density, TrafficThresholds thresholds) {
        if (!Double.isFinite(density) || density < 0) {
            throw new IllegalArgumentException("Density must be finite and non-negative");
        }
        if (thresholds == null) {
            throw new IllegalArgumentException("Traffic thresholds are required");
        }
    }
}