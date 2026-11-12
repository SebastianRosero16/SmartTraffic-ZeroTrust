package edu.smarttraffic.processor.domain.density;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeWeightedDensityStrategyTest {
    private final Instant evaluatedAt = Instant.parse("2026-11-11T12:00:10Z");

    @Test
    void givesMoreWeightToMoreRecentReadings() {
        var strategy = new TimeWeightedDensityStrategy(Duration.ofSeconds(10));
        var readings = List.of(
                reading(4, evaluatedAt.minusSeconds(8)),
                reading(8, evaluatedAt.minusSeconds(2)));

        assertEquals(7.2, strategy.calculate(readings, evaluatedAt), 0.000001);
    }

    @Test
    void ignoresReadingsOutsideWindow() {
        var strategy = new TimeWeightedDensityStrategy(Duration.ofSeconds(10));
        var readings = List.of(
                reading(100, evaluatedAt.minusSeconds(11)),
                reading(5, evaluatedAt.minusSeconds(5)));

        assertEquals(5.0, strategy.calculate(readings, evaluatedAt));
    }

    @Test
    void rejectsInvalidWindowAndInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWeightedDensityStrategy(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWeightedDensityStrategy(Duration.ofSeconds(-1)));

        var strategy = new TimeWeightedDensityStrategy(Duration.ofSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> strategy.calculate(List.of(), evaluatedAt));
        assertThrows(IllegalArgumentException.class,
                () -> strategy.calculate(List.of(reading(1, evaluatedAt.plusSeconds(1))), evaluatedAt));
        assertThrows(IllegalArgumentException.class,
                () -> strategy.calculate(List.of(reading(1, evaluatedAt.minusSeconds(10))), evaluatedAt));
    }

    private TrafficReading reading(int vehicleCount, Instant occurredAt) {
        return new TrafficReading(
                "event-" + vehicleCount + "-" + occurredAt,
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                occurredAt,
                vehicleCount);
    }
}