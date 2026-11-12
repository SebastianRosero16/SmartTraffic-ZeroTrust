package edu.smarttraffic.processor.domain.density;

import edu.smarttraffic.contracts.TrafficReading;
import java.time.Instant;
import java.util.List;

final class DensityCalculationSupport {
    private DensityCalculationSupport() {
    }

    static void validateInputs(List<TrafficReading> readings, Instant evaluatedAt) {
        if (readings == null || readings.isEmpty()) {
            throw new IllegalArgumentException("At least one reading is required");
        }
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("Evaluation time is required");
        }
        if (readings.stream().anyMatch(reading -> reading == null)) {
            throw new IllegalArgumentException("Readings must not contain null values");
        }
    }
}