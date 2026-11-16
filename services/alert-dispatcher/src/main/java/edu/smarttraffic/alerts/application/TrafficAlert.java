package edu.smarttraffic.alerts.application;

import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;

public record TrafficAlert(
        ZoneId zoneId,
        TrafficStatus previousStatus,
        TrafficStatus currentStatus,
        Instant measuredAt) {

    public TrafficAlert {
        if (zoneId == null || previousStatus == null || currentStatus == null || measuredAt == null) {
            throw new IllegalArgumentException("Alert transition fields are required");
        }
        if (previousStatus == currentStatus) {
            throw new IllegalArgumentException("Alert must represent a status change");
        }
    }
}
