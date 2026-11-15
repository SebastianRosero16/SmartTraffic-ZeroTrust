package edu.smarttraffic.kafkaserdes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonSerdeTest {
    @Test
    void roundTripsTrafficReadingIncludingNestedRecordsAndInstant() {
        var serde = new JsonSerde<>(TrafficReading.class);
        var reading = new TrafficReading(
                "event-1",
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                Instant.parse("2026-11-14T07:00:00Z"),
                12);

        byte[] serialized = serde.serializer().serialize("traffic_readings", reading);

        assertEquals(
                reading,
                serde.deserializer().deserialize("traffic_readings", serialized));
    }
}
