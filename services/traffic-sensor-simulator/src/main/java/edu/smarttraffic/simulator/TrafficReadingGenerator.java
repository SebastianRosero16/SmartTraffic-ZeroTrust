package edu.smarttraffic.simulator;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class TrafficReadingGenerator {
    private final List<SensorId> sensors;
    private final List<ZoneId> zones;
    private final int minimumVehicles;
    private final int maximumVehicles;
    private final Clock clock;
    private final RandomGenerator randomGenerator;

    public TrafficReadingGenerator(
            List<SensorId> sensors,
            List<ZoneId> zones,
            int minimumVehicles,
            int maximumVehicles,
            Clock clock,
            RandomGenerator randomGenerator) {
        this.sensors = copyRequiredIdentifiers(sensors, "Sensors");
        this.zones = copyRequiredIdentifiers(zones, "Zones");
        if (minimumVehicles < 0 || maximumVehicles < 0) {
            throw new IllegalArgumentException("Vehicle limits must not be negative");
        }
        if (minimumVehicles > maximumVehicles) {
            throw new IllegalArgumentException("Minimum vehicles must not exceed maximum vehicles");
        }

        this.minimumVehicles = minimumVehicles;
        this.maximumVehicles = maximumVehicles;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.randomGenerator = Objects.requireNonNull(randomGenerator, "Random generator is required");
    }

    public TrafficReading generate() {
        SensorId sensorId = sensors.get(randomGenerator.nextInt(sensors.size()));
        ZoneId zoneId = zones.get(randomGenerator.nextInt(zones.size()));
        int vehicleCount = (int) randomGenerator.nextLong(minimumVehicles, (long) maximumVehicles + 1);
        String eventId = new UUID(randomGenerator.nextLong(), randomGenerator.nextLong()).toString();

        return new TrafficReading(eventId, sensorId, zoneId, clock.instant(), vehicleCount);
    }

    private static <T> List<T> copyRequiredIdentifiers(List<T> identifiers, String label) {
        Objects.requireNonNull(identifiers, label + " are required");
        if (identifiers.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        if (identifiers.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " must not contain null values");
        }
        return List.copyOf(identifiers);
    }
}
