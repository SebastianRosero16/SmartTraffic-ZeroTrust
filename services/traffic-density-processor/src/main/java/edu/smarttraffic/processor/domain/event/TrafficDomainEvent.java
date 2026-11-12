package edu.smarttraffic.processor.domain.event;

import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;

public sealed interface TrafficDomainEvent
        permits TrafficStatusChangedEvent, CriticalDensityReachedEvent {
    ZoneId zoneId();

    Instant occurredAt();
}