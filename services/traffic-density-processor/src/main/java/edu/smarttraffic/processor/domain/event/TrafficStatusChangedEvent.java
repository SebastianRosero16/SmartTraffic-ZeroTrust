package edu.smarttraffic.processor.domain.event;

import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;

public record TrafficStatusChangedEvent(
        ZoneId zoneId,
        Instant occurredAt,
        TrafficStatus previousStatus,
        TrafficStatus currentStatus,
        double densityValue) implements TrafficDomainEvent {
}