package edu.smarttraffic.processor.domain.density;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AverageDensityStrategyTest {
    private final AverageDensityStrategy strategy = new AverageDensityStrategy();
    private final Instant evaluatedAt = Instant.parse("2026-11-11T12:00:00Z");

    @Test
    void calculatesSimpleAverage() {
        var readings = List.of(reading(10, evaluatedAt), reading(20, evaluatedAt));

        assertEquals(15.0, strategy.calculate(readings, evaluatedAt));
    }

    @Test
    void rejectsMissingInputs() {
        assertThrows(IllegalArgumentException.class, () -> strategy.calculate(null, evaluatedAt));
        assertThrows(IllegalArgumentException.class, () -> strategy.calculate(List.of(), evaluatedAt));
        assertThrows(IllegalArgumentException.class, () -> strategy.calculate(List.of(reading(1, evaluatedAt)), null));
        assertThrows(IllegalArgumentException.class,
                () -> strategy.calculate(java.util.Arrays.asList((TrafficReading) null), evaluatedAt));
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