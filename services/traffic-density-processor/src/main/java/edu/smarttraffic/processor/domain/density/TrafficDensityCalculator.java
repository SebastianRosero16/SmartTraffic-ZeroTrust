package edu.smarttraffic.processor.domain.density;

import edu.smarttraffic.contracts.TrafficReading;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class TrafficDensityCalculator {
    private final DensityCalculationStrategy strategy;

    public TrafficDensityCalculator(DensityCalculationStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "Density strategy is required");
    }

    public double calculate(List<TrafficReading> readings, Instant evaluatedAt) {
        return strategy.calculate(readings, evaluatedAt);
    }
}