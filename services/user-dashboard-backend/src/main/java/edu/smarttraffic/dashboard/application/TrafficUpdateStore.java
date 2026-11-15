package edu.smarttraffic.dashboard.application;

import edu.smarttraffic.contracts.TrafficUpdate;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TrafficUpdateStore {
    private final ConcurrentMap<String, TrafficUpdate> latestByZone = new ConcurrentHashMap<>();

    public boolean accept(TrafficUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("Traffic update is required");
        }

        AtomicBoolean accepted = new AtomicBoolean();
        latestByZone.compute(update.zoneId().value(), (zoneId, current) -> {
            if (current != null && update.measuredAt().isBefore(current.measuredAt())) {
                return current;
            }
            accepted.set(true);
            return update;
        });
        return accepted.get();
    }

    public List<TrafficUpdate> snapshot() {
        return latestByZone.values().stream()
                .sorted(Comparator.comparing(update -> update.zoneId().value()))
                .toList();
    }
}
