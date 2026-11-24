package edu.smarttraffic.processor.domain.density;

public record DensityWindowStatistics(
        long totalVehicleCount,
        long readingCount,
        double weightedVehicleOffsetSeconds,
        double timestampOffsetSeconds,
        double elapsedSeconds) {

    public DensityWindowStatistics {
        if (totalVehicleCount < 0) {
            throw new IllegalArgumentException("Total vehicle count must not be negative");
        }
        if (readingCount <= 0) {
            throw new IllegalArgumentException("At least one reading is required");
        }
        if (!isNonNegativeFinite(weightedVehicleOffsetSeconds)
                || !isNonNegativeFinite(timestampOffsetSeconds)
                || !isNonNegativeFinite(elapsedSeconds)) {
            throw new IllegalArgumentException("Window statistics must be finite and non-negative");
        }
    }

    private static boolean isNonNegativeFinite(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
