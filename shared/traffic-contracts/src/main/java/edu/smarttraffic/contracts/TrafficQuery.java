package edu.smarttraffic.contracts;

import java.util.List;

public record TrafficQuery(String requestId, String instanceId, List<ZoneId> zoneIds) {
    public TrafficQuery {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID must not be blank");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("Instance ID must not be blank");
        }
        if (zoneIds == null || zoneIds.isEmpty()) {
            throw new IllegalArgumentException("At least one zone ID is required");
        }
        zoneIds = List.copyOf(zoneIds);
    }
}