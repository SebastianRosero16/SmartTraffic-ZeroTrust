package edu.smarttraffic.processor.domain.state;

public record TrafficThresholds(double moderateMinimum, double congestedMinimum) {
    public TrafficThresholds {
        if (!Double.isFinite(moderateMinimum) || !Double.isFinite(congestedMinimum)) {
            throw new IllegalArgumentException("Traffic thresholds must be finite");
        }
        if (moderateMinimum < 0 || congestedMinimum < 0) {
            throw new IllegalArgumentException("Traffic thresholds must not be negative");
        }
        if (moderateMinimum >= congestedMinimum) {
            throw new IllegalArgumentException("Moderate threshold must be lower than congested threshold");
        }
    }
}