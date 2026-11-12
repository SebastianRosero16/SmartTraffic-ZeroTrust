package edu.smarttraffic.processor.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.processor.domain.density.AverageDensityStrategy;
import edu.smarttraffic.processor.domain.event.CriticalDensityReachedEvent;
import edu.smarttraffic.processor.domain.event.TrafficStatusChangedEvent;
import edu.smarttraffic.processor.domain.state.TrafficThresholds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneTrafficAggregateTest {
    private static final Instant EVALUATED_AT = Instant.parse("2026-11-11T12:00:00Z");
    private static final ZoneId ZONE_ID = new ZoneId("zone-1");

    @Test
    void processesReadingsAndUpdatesItsMetricsAndState() {
        var aggregate = new ZoneTrafficAggregate(
                ZONE_ID,
                new TrafficThresholds(5, 10),
                List.of());

        var status = aggregate.processReadings(
                List.of(reading("event-1", ZONE_ID, 4), reading("event-2", ZONE_ID, 6)),
                new AverageDensityStrategy(),
                EVALUATED_AT);

        assertEquals(TrafficStatus.MODERADA, status);
        assertEquals(10, aggregate.currentMetrics().vehicleCount());
        assertEquals(5.0, aggregate.currentMetrics().densityValue());
    }

    @Test
    void notifiesOnStateChangesAndOnlyOnceWhenEnteringCongestion() {
        var events = new ArrayList<Object>();
        var aggregate = new ZoneTrafficAggregate(
                ZONE_ID,
                new TrafficThresholds(5, 10),
                List.of(event -> events.add(event)));
        var strategy = new AverageDensityStrategy();

        aggregate.processReadings(List.of(reading("event-1", ZONE_ID, 10)), strategy, EVALUATED_AT);
        aggregate.processReadings(List.of(reading("event-2", ZONE_ID, 12)), strategy, EVALUATED_AT.plusSeconds(1));
        aggregate.processReadings(List.of(reading("event-3", ZONE_ID, 4)), strategy, EVALUATED_AT.plusSeconds(2));

        assertEquals(3, events.size());
        assertEquals(2, events.stream().filter(TrafficStatusChangedEvent.class::isInstance).count());
        assertEquals(1, events.stream().filter(CriticalDensityReachedEvent.class::isInstance).count());
    }

    @Test
    void rejectsReadingsFromAnotherZoneWithoutChangingAggregateState() {
        var aggregate = new ZoneTrafficAggregate(
                ZONE_ID,
                new TrafficThresholds(5, 10),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> aggregate.processReadings(
                List.of(reading("event-1", new ZoneId("zone-2"), 8)),
                new AverageDensityStrategy(),
                EVALUATED_AT));

        assertEquals(TrafficStatus.DESPEJADA, aggregate.currentStatus());
        assertEquals(0, aggregate.currentMetrics().vehicleCount());
    }

    @Test
    void rejectsEmptyReadings() {
        var aggregate = new ZoneTrafficAggregate(
                ZONE_ID,
                new TrafficThresholds(5, 10),
                List.of());

        assertThrows(IllegalArgumentException.class,
                () -> aggregate.processReadings(List.of(), new AverageDensityStrategy(), EVALUATED_AT));
    }

    private TrafficReading reading(String eventId, ZoneId zoneId, int vehicleCount) {
        return new TrafficReading(
                eventId,
                new SensorId("sensor-1"),
                zoneId,
                EVALUATED_AT,
                vehicleCount);
    }
}