package edu.smarttraffic.processor.domain.density;

import edu.smarttraffic.contracts.TrafficReading;
import java.time.Instant;
import java.util.List;

public final class AverageDensityStrategy implements DensityCalculationStrategy {
    @Override
    public double calculate(List<TrafficReading> readings, Instant evaluatedAt) {
        DensityCalculationSupport.validateInputs(readings, evaluatedAt);

        return readings.stream()
                .mapToInt(TrafficReading::vehicleCount)
                .average()
                .orElseThrow(() -> new IllegalArgumentException("At least one reading is required"));
    }
}