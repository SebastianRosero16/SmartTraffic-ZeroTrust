package edu.smarttraffic.alerts.application;

import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

public final class TrafficStateStore {
    private final ConcurrentMap<String, TrafficUpdate> latestByZone = new ConcurrentHashMap<>();

    public Acceptance accept(TrafficUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("Traffic update is required");
        }

        AtomicReference<Acceptance> result = new AtomicReference<>();
        latestByZone.compute(update.zoneId().value(), (zone, current) -> {
            if (current != null && update.measuredAt().isBefore(current.measuredAt())) {
                result.set(new Acceptance(false, Optional.empty(), current));
                return current;
            }

            TrafficStatus previousStatus = current == null
                    ? TrafficStatus.DESPEJADA
                    : current.status();
            Optional<TrafficAlert> alert = previousStatus == update.status()
                    ? Optional.empty()
                    : Optional.of(new TrafficAlert(
                            update.zoneId(),
                            previousStatus,
                            update.status(),
                            update.measuredAt()));
            result.set(new Acceptance(true, alert, update));
            return update;
        });
        return result.get();
    }

    public Optional<TrafficUpdate> findLatest(ZoneId zoneId) {
        if (zoneId == null) {
            throw new IllegalArgumentException("Zone ID is required");
        }
        return Optional.ofNullable(latestByZone.get(zoneId.value()));
    }

    public record Acceptance(
            boolean accepted,
            Optional<TrafficAlert> alert,
            TrafficUpdate latestUpdate) {
    }
}
