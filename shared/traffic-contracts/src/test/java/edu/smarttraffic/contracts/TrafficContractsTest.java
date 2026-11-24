package edu.smarttraffic.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrafficContractsTest {
    @Test
    void rejectsNegativeOrNonFiniteMetrics() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficMetrics(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TrafficMetrics(0, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new TrafficMetrics(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new TrafficMetrics(0, Double.POSITIVE_INFINITY));
    }

    @Test
    void rejectsIncompleteTrafficUpdate() {
        var metrics = new TrafficMetrics(10, 0.5);

        assertThrows(IllegalArgumentException.class,
                () -> new TrafficUpdate(null, Instant.EPOCH, metrics, TrafficStatus.MODERADA));
        assertThrows(IllegalArgumentException.class,
                () -> new TrafficUpdate(new ZoneId("zone-1"), null, metrics, TrafficStatus.MODERADA));
        assertThrows(IllegalArgumentException.class,
                () -> new TrafficUpdate(new ZoneId("zone-1"), Instant.EPOCH, null, TrafficStatus.MODERADA));
        assertThrows(IllegalArgumentException.class,
                () -> new TrafficUpdate(new ZoneId("zone-1"), Instant.EPOCH, metrics, null));
    }

    @Test
    void rejectsIncompleteTrafficAlert() {
        var zoneId = new ZoneId("zone-1");

        assertThrows(IllegalArgumentException.class, () -> new TrafficAlert(
                " ", zoneId, Instant.EPOCH, TrafficStatus.MODERADA, TrafficStatus.CONGESTIONADA, "Alert"));
        assertThrows(IllegalArgumentException.class, () -> new TrafficAlert(
                "alert-1", null, Instant.EPOCH, TrafficStatus.MODERADA, TrafficStatus.CONGESTIONADA, "Alert"));
        assertThrows(IllegalArgumentException.class, () -> new TrafficAlert(
                "alert-1", zoneId, Instant.EPOCH, null, TrafficStatus.CONGESTIONADA, "Alert"));
        assertThrows(IllegalArgumentException.class, () -> new TrafficAlert(
                "alert-1", zoneId, Instant.EPOCH, TrafficStatus.MODERADA, null, "Alert"));
        assertThrows(IllegalArgumentException.class, () -> new TrafficAlert(
                "alert-1", zoneId, Instant.EPOCH, TrafficStatus.MODERADA, TrafficStatus.CONGESTIONADA, " "));
    }

    @Test
    void queryCopiesRequestedZonesAndRejectsAnEmptySelection() {
        var requestedZones = new ArrayList<>(List.of(new ZoneId("zone-1")));
        var query = new TrafficQuery("request-1", "instance-1", requestedZones);
        requestedZones.add(new ZoneId("zone-2"));

        assertEquals(1, query.zoneIds().size());
        assertThrows(UnsupportedOperationException.class,
                () -> query.zoneIds().add(new ZoneId("zone-3")));
        assertThrows(IllegalArgumentException.class,
                () -> new TrafficQuery("request-1", "instance-1", List.of()));
    }

    @Test
    void queryResponseCopiesItsUpdates() {
        var update = new TrafficUpdate(
                new ZoneId("zone-1"),
                Instant.EPOCH,
                new TrafficMetrics(10, 0.5),
                TrafficStatus.MODERADA);
        var sourceUpdates = new ArrayList<>(List.of(update));
        var response = new TrafficQueryResponse("request-1", sourceUpdates);
        sourceUpdates.clear();

        assertEquals(1, response.updates().size());
        assertThrows(UnsupportedOperationException.class,
                () -> response.updates().clear());
    }
}