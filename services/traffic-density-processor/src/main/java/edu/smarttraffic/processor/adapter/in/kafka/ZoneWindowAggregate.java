package edu.smarttraffic.processor.adapter.in.kafka;

import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.processor.domain.density.AverageDensityStrategy;
import java.time.Instant;
import java.util.Objects;

public record ZoneWindowAggregate(
        long totalVehicleCount,
        long readingCount,
        Instant latestOccurredAt) {

    public ZoneWindowAggregate {
        if (totalVehicleCount < 0 || readingCount < 0) {
            throw new IllegalArgumentException("Aggregate counts must not be negative");
        }
        if ((readingCount == 0) != (latestOccurredAt == null)) {
            throw new IllegalArgumentException("Empty aggregates must not have a timestamp");
        }
    }

    public static ZoneWindowAggregate empty() {
        return new ZoneWindowAggregate(0, 0, null);
    }

    public ZoneWindowAggregate add(TrafficReading reading) {
        Objects.requireNonNull(reading, "Traffic reading is required");
        long nextTotal = Math.addExact(totalVehicleCount, reading.vehicleCount());
        long nextCount = Math.addExact(readingCount, 1);
        Instant nextLatest = latestOccurredAt == null || reading.occurredAt().isAfter(latestOccurredAt)
                ? reading.occurredAt()
                : latestOccurredAt;
        return new ZoneWindowAggregate(nextTotal, nextCount, nextLatest);
    }

    public double averageVehicleCount() {
        return new AverageDensityStrategy().calculate(totalVehicleCount, readingCount);
    }
}
