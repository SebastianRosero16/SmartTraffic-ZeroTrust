package edu.smarttraffic.processor.domain.density;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrafficDensityCalculatorTest {
    @Test
    void delegatesCalculationToInjectedStrategy() {
        var evaluatedAt = Instant.parse("2026-11-11T12:00:10Z");
        var readings = List.of(
                reading(4, evaluatedAt.minusSeconds(8)),
                reading(8, evaluatedAt.minusSeconds(2)));

        var simpleCalculator = new TrafficDensityCalculator(new AverageDensityStrategy());
        var weightedCalculator = new TrafficDensityCalculator(
                new TimeWeightedDensityStrategy(Duration.ofSeconds(10)));

        assertEquals(6.0, simpleCalculator.calculate(readings, evaluatedAt));
        assertEquals(7.2, weightedCalculator.calculate(readings, evaluatedAt), 0.000001);
    }

    private TrafficReading reading(int vehicleCount, Instant occurredAt) {
        return new TrafficReading(
                "event-" + vehicleCount,
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                occurredAt,
                vehicleCount);
    }
}