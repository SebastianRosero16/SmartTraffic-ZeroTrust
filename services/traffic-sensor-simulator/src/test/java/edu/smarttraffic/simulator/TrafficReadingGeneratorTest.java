package edu.smarttraffic.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TrafficReadingGeneratorTest {
    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-11T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final List<SensorId> SENSORS = List.of(new SensorId("sensor-1"), new SensorId("sensor-2"));
    private static final List<ZoneId> ZONES = List.of(new ZoneId("zone-1"), new ZoneId("zone-2"));

    @Test
    void generatesReadingsFromTheContractsWithConfiguredValues() {
        var generator = generator(12, 24, 17);

        TrafficReading reading = generator.generate();

        assertNotNull(reading);
        assertFalse(reading.eventId().isBlank());
        assertTrue(SENSORS.contains(reading.sensorId()));
        assertTrue(ZONES.contains(reading.zoneId()));
        assertEquals(FIXED_INSTANT, reading.occurredAt());
        assertTrue(reading.vehicleCount() >= 12 && reading.vehicleCount() <= 24);
    }

    @Test
    void producesReproducibleReadingsWithASeededRandomGenerator() {
        var firstGenerator = generator(0, 100, 42);
        var secondGenerator = generator(0, 100, 42);

        List<TrafficReading> firstReadings = generateReadings(firstGenerator, 20);
        List<TrafficReading> secondReadings = generateReadings(secondGenerator, 20);

        assertEquals(firstReadings, secondReadings);
        assertEquals(20, firstReadings.stream().map(TrafficReading::eventId).distinct().count());
    }

    @Test
    void allowsEqualNonNegativeVehicleLimitsIncludingIntegerMaximum() {
        var generator = generator(Integer.MAX_VALUE, Integer.MAX_VALUE, 9);

        assertEquals(Integer.MAX_VALUE, generator.generate().vehicleCount());
    }

    @Test
    void rejectsEmptySensorOrZoneLists() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficReadingGenerator(
                List.of(), ZONES, 0, 10, FIXED_CLOCK, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> new TrafficReadingGenerator(
                SENSORS, List.of(), 0, 10, FIXED_CLOCK, new Random(1)));
    }

    @Test
    void rejectsNullSensorOrZoneIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficReadingGenerator(
                Arrays.asList(SENSORS.get(0), null), ZONES, 0, 10, FIXED_CLOCK, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> new TrafficReadingGenerator(
                SENSORS, Arrays.asList(ZONES.get(0), null), 0, 10, FIXED_CLOCK, new Random(1)));
    }

    @Test
    void rejectsNegativeOrInvertedVehicleLimits() {
        assertThrows(IllegalArgumentException.class, () -> generator(-1, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> generator(0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> generator(11, 10, 1));
    }

    private static TrafficReadingGenerator generator(int minimumVehicles, int maximumVehicles, long seed) {
        return new TrafficReadingGenerator(
                SENSORS,
                ZONES,
                minimumVehicles,
                maximumVehicles,
                FIXED_CLOCK,
                new Random(seed));
    }

    private static List<TrafficReading> generateReadings(TrafficReadingGenerator generator, int count) {
        return java.util.stream.Stream.generate(generator::generate)
                .limit(count)
                .toList();
    }
}
