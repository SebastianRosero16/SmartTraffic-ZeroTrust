package edu.smarttraffic.alerts.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrafficStateStoreTest {
    private final TrafficStateStore store = new TrafficStateStore();

    @Test
    void alertsOnEntryIntoAndRecoveryFromNonClearStatuses() {
        TrafficStateStore.Acceptance congestion = store.accept(update(
                "zone-a", "2026-11-14T20:00:00Z", TrafficStatus.CONGESTIONADA));
        TrafficStateStore.Acceptance recovery = store.accept(update(
                "zone-a", "2026-11-14T20:05:00Z", TrafficStatus.DESPEJADA));

        assertEquals(TrafficStatus.DESPEJADA, congestion.alert().orElseThrow().previousStatus());
        assertEquals(TrafficStatus.CONGESTIONADA, congestion.alert().orElseThrow().currentStatus());
        assertEquals(TrafficStatus.CONGESTIONADA, recovery.alert().orElseThrow().previousStatus());
        assertEquals(TrafficStatus.DESPEJADA, recovery.alert().orElseThrow().currentStatus());
    }

    @Test
    void doesNotRepeatAlertForSameStatusAndIgnoresStaleUpdates() {
        TrafficUpdate current = update("zone-a", "2026-11-14T20:00:00Z", TrafficStatus.MODERADA);
        store.accept(current);

        TrafficStateStore.Acceptance duplicate = store.accept(update(
                "zone-a", "2026-11-14T20:01:00Z", TrafficStatus.MODERADA));
        TrafficStateStore.Acceptance stale = store.accept(update(
                "zone-a", "2026-11-14T19:00:00Z", TrafficStatus.CONGESTIONADA));

        assertTrue(duplicate.alert().isEmpty());
        assertFalse(stale.accepted());
        assertEquals(current.zoneId(), store.findLatest(new ZoneId("zone-a")).orElseThrow().zoneId());
        assertEquals(
                Instant.parse("2026-11-14T20:01:00Z"),
                store.findLatest(new ZoneId("zone-a")).orElseThrow().measuredAt());
    }

    private static TrafficUpdate update(String zone, String measuredAt, TrafficStatus status) {
        return new TrafficUpdate(
                new ZoneId(zone),
                Instant.parse(measuredAt),
                new TrafficMetrics(17, 8.5),
                status);
    }
}
