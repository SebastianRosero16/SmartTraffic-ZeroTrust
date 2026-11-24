package edu.smarttraffic.contracts;

import java.util.List;

public record TrafficQueryResponse(String requestId, List<TrafficUpdate> updates) {
    public TrafficQueryResponse {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID must not be blank");
        }
        if (updates == null) {
            throw new IllegalArgumentException("Updates are required");
        }
        updates = List.copyOf(updates);
    }
}