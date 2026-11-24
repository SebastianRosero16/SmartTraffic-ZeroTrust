package edu.smarttraffic.processor.adapter.in.kafka;

import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.processor.domain.density.DensityWindowStatistics;
import java.time.Instant;
import java.util.Objects;

public record ZoneWindowAggregate(
        long totalVehicleCount,
        long readingCount,
        Instant earliestOccurredAt,
        double weightedVehicleOffsetSeconds,
        double timestampOffsetSeconds,
        Instant latestOccurredAt) {

    public ZoneWindowAggregate {
        if (totalVehicleCount < 0 || readingCount < 0) {
            throw new IllegalArgumentException("Aggregate counts must not be negative");
        }
        if ((readingCount == 0) != (earliestOccurredAt == null)
                || (readingCount == 0) != (latestOccurredAt == null)) {
            throw new IllegalArgumentException("Empty aggregates must not have a timestamp");
        }
        if (readingCount > 0 && latestOccurredAt.isBefore(earliestOccurredAt)) {
            throw new IllegalArgumentException("Latest reading time must not precede the earliest time");
        }
        if (!Double.isFinite(weightedVehicleOffsetSeconds)
                || weightedVehicleOffsetSeconds < 0
                || !Double.isFinite(timestampOffsetSeconds)
                || timestampOffsetSeconds < 0) {
            throw new IllegalArgumentException("Aggregate time offsets must be finite and non-negative");
        }
        if (readingCount == 0 && (weightedVehicleOffsetSeconds != 0 || timestampOffsetSeconds != 0)) {
            throw new IllegalArgumentException("Empty aggregates must not have time offsets");
        }
    }

    public static ZoneWindowAggregate empty() {
        return new ZoneWindowAggregate(0, 0, null, 0, 0, null);
    }

    public ZoneWindowAggregate add(TrafficReading reading) {
        Objects.requireNonNull(reading, "Traffic reading is required");
        long nextTotal = Math.addExact(totalVehicleCount, reading.vehicleCount());
        long nextCount = Math.addExact(readingCount, 1);
        Instant nextEarliest = earliestOccurredAt;
        double nextWeightedVehicleOffset = weightedVehicleOffsetSeconds;
        double nextTimestampOffset = timestampOffsetSeconds;
        double readingOffsetSeconds = 0;

        if (nextEarliest == null) {
            nextEarliest = reading.occurredAt();
        } else {
            readingOffsetSeconds = secondsBetween(nextEarliest, reading.occurredAt());
            if (readingOffsetSeconds < 0) {
                double shiftSeconds = -readingOffsetSeconds;
                nextWeightedVehicleOffset += totalVehicleCount * shiftSeconds;
                nextTimestampOffset += readingCount * shiftSeconds;
                nextEarliest = reading.occurredAt();
                readingOffsetSeconds = 0;
            }
        }

        nextWeightedVehicleOffset += reading.vehicleCount() * readingOffsetSeconds;
        nextTimestampOffset += readingOffsetSeconds;
        Instant nextLatest = latestOccurredAt == null || reading.occurredAt().isAfter(latestOccurredAt)
                ? reading.occurredAt()
                : latestOccurredAt;
        return new ZoneWindowAggregate(
                nextTotal,
                nextCount,
                nextEarliest,
                nextWeightedVehicleOffset,
                nextTimestampOffset,
                nextLatest);
    }

    public DensityWindowStatistics densityStatistics() {
        if (readingCount == 0) {
            throw new IllegalStateException("An empty aggregate has no density statistics");
        }
        return new DensityWindowStatistics(
                totalVehicleCount,
                readingCount,
                weightedVehicleOffsetSeconds,
                timestampOffsetSeconds,
                secondsBetween(earliestOccurredAt, latestOccurredAt));
    }

    private static double secondsBetween(Instant start, Instant end) {
        var duration = java.time.Duration.between(start, end);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }
}
