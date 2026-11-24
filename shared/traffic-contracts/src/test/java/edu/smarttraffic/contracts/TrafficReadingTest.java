package edu.smarttraffic.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrafficReadingTest {
    @Test
    void createsReadingWhenAllValuesAreValid() {
        var occurredAt = Instant.parse("2026-11-11T12:00:00Z");

        var reading = new TrafficReading(
                "event-1",
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                occurredAt,
                12);

        assertEquals("event-1", reading.eventId());
        assertEquals(12, reading.vehicleCount());
        assertEquals(occurredAt, reading.occurredAt());
    }

    @Test
    void rejectsBlankEventId() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficReading(
                " ",
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                Instant.EPOCH,
                0));
    }

    @Test
    void rejectsMissingIdentifiersAndTimestamp() {
        var sensorId = new SensorId("sensor-1");
        var zoneId = new ZoneId("zone-1");

        assertThrows(IllegalArgumentException.class,
                () -> new TrafficReading("event-1", null, zoneId, Instant.EPOCH, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TrafficReading("event-1", sensorId, null, Instant.EPOCH, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TrafficReading("event-1", sensorId, zoneId, null, 0));
    }

    @Test
    void rejectsNegativeVehicleCount() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficReading(
                "event-1",
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                Instant.EPOCH,
                -1));
    }

    @Test
    void rejectsBlankSensorAndZoneIds() {
        assertThrows(IllegalArgumentException.class, () -> new SensorId(" "));
        assertThrows(IllegalArgumentException.class, () -> new ZoneId(" "));
    }
}