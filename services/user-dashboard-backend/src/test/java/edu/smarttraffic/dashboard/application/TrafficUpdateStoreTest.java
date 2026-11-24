package edu.smarttraffic.dashboard.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrafficUpdateStoreTest {
    private final TrafficUpdateStore store = new TrafficUpdateStore();

    @Test
    void retainsNewestUpdatePerZoneAndAllowsCorrectionsForTheSameWindow() {
        TrafficUpdate first = update("zone-a", "2026-11-14T20:00:00Z", 20);
        TrafficUpdate correction = update("zone-a", "2026-11-14T20:00:00Z", 25);
        TrafficUpdate stale = update("zone-a", "2026-11-14T19:00:00Z", 10);

        assertTrue(store.accept(first));
        assertTrue(store.accept(correction));
        assertFalse(store.accept(stale));

        assertEquals(correction, store.snapshot().get(0));
    }

    @Test
    void returnsOneSortedLatestUpdatePerZone() {
        store.accept(update("zone-b", "2026-11-14T20:00:00Z", 12));
        store.accept(update("zone-a", "2026-11-14T20:00:00Z", 8));

        assertEquals(
                java.util.List.of("zone-a", "zone-b"),
                store.snapshot().stream().map(update -> update.zoneId().value()).toList());
    }

    private static TrafficUpdate update(String zone, String measuredAt, int vehicleCount) {
        return new TrafficUpdate(
                new ZoneId(zone),
                Instant.parse(measuredAt),
                new TrafficMetrics(vehicleCount, vehicleCount / 2.0),
                TrafficStatus.MODERADA);
    }
}
