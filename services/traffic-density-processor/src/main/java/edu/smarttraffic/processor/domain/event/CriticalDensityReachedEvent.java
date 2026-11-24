package edu.smarttraffic.processor.domain.event;

import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;

public record CriticalDensityReachedEvent(
        ZoneId zoneId,
        Instant occurredAt,
        double densityValue) implements TrafficDomainEvent {
}