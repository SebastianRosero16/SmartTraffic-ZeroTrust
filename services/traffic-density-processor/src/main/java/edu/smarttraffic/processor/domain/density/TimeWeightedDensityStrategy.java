package edu.smarttraffic.processor.domain.density;

import edu.smarttraffic.contracts.TrafficReading;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Proposed linear recency weighting: a reading's weight is the remaining
 * duration in the window at evaluation time. The formula requires team approval.
 */
public final class TimeWeightedDensityStrategy implements DensityCalculationStrategy {
    private final Duration window;
    private final long windowNanos;

    public TimeWeightedDensityStrategy(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Window must be a positive duration");
        }
        try {
            this.windowNanos = window.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Window is too large", exception);
        }
        this.window = window;
    }

    @Override
    public double calculate(List<TrafficReading> readings, Instant evaluatedAt) {
        DensityCalculationSupport.validateInputs(readings, evaluatedAt);

        double weightedVehicleCount = 0;
        double totalWeight = 0;

        for (TrafficReading reading : readings) {
            Duration age = Duration.between(reading.occurredAt(), evaluatedAt);
            if (age.isNegative()) {
                throw new IllegalArgumentException("Reading time must not be in the future");
            }
            if (age.compareTo(window) >= 0) {
                continue;
            }

            long weight = windowNanos - age.toNanos();
            weightedVehicleCount += reading.vehicleCount() * (double) weight;
            totalWeight += weight;
        }

        if (totalWeight == 0) {
            throw new IllegalArgumentException("No readings fall inside the calculation window");
        }

        return weightedVehicleCount / totalWeight;
    }

    @Override
    public double calculateWindow(DensityWindowStatistics statistics) {
        if (statistics == null) {
            throw new IllegalArgumentException("Window statistics are required");
        }

        double windowSeconds = windowNanos / 1_000_000_000.0;
        if (statistics.elapsedSeconds() > windowSeconds) {
            throw new IllegalArgumentException("Readings span more than the calculation window");
        }

        double weightAtLatestReading = windowSeconds - statistics.elapsedSeconds();
        double weightedVehicleCount = statistics.totalVehicleCount() * weightAtLatestReading
                + statistics.weightedVehicleOffsetSeconds();
        double totalWeight = statistics.readingCount() * weightAtLatestReading
                + statistics.timestampOffsetSeconds();
        if (totalWeight == 0) {
            throw new IllegalArgumentException("No readings fall inside the calculation window");
        }

        return weightedVehicleCount / totalWeight;
    }
}