package edu.smarttraffic.processor.domain.density;

import edu.smarttraffic.contracts.TrafficReading;
import java.time.Instant;
import java.util.List;

public final class AverageDensityStrategy implements DensityCalculationStrategy {
    public double calculate(long totalVehicleCount, long readingCount) {
        if (totalVehicleCount < 0) {
            throw new IllegalArgumentException("Total vehicle count must not be negative");
        }
        if (readingCount <= 0) {
            throw new IllegalArgumentException("At least one reading is required");
        }
        return totalVehicleCount / (double) readingCount;
    }

    @Override
    public double calculateWindow(DensityWindowStatistics statistics) {
        if (statistics == null) {
            throw new IllegalArgumentException("Window statistics are required");
        }
        return calculate(statistics.totalVehicleCount(), statistics.readingCount());
    }

    @Override
    public double calculate(List<TrafficReading> readings, Instant evaluatedAt) {
        DensityCalculationSupport.validateInputs(readings, evaluatedAt);

        return readings.stream()
                .mapToInt(TrafficReading::vehicleCount)
                .average()
                .orElseThrow(() -> new IllegalArgumentException("At least one reading is required"));
    }
}