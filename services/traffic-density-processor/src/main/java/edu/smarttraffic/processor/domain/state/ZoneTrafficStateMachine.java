package edu.smarttraffic.processor.domain.state;

import edu.smarttraffic.contracts.TrafficStatus;
import java.util.Objects;

public final class ZoneTrafficStateMachine {
    private final TrafficThresholds thresholds;
    private TrafficState currentState;

    public ZoneTrafficStateMachine(TrafficThresholds thresholds) {
        this(thresholds, TrafficStatus.DESPEJADA);
    }

    public ZoneTrafficStateMachine(TrafficThresholds thresholds, TrafficStatus initialStatus) {
        this.thresholds = Objects.requireNonNull(thresholds, "Traffic thresholds are required");
        this.currentState = switch (Objects.requireNonNull(initialStatus, "Initial status is required")) {
            case DESPEJADA -> new ClearTrafficState();
            case MODERADA -> new ModerateTrafficState();
            case CONGESTIONADA -> new CongestedTrafficState();
        };
    }

    public TrafficStatus currentStatus() {
        return currentState.status();
    }

    public TrafficStatus updateDensity(double density) {
        currentState = currentState.transition(density, thresholds);
        return currentState.status();
    }
}