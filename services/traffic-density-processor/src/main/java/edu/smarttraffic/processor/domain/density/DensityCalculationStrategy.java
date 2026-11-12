package edu.smarttraffic.processor.domain.density;

import edu.smarttraffic.contracts.TrafficReading;
import java.time.Instant;
import java.util.List;

public interface DensityCalculationStrategy {
    double calculate(List<TrafficReading> readings, Instant evaluatedAt);
}