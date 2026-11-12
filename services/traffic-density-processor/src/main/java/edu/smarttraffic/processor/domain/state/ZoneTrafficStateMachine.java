package edu.smarttraffic.processor.domain.state;

import edu.smarttraffic.contracts.TrafficStatus;
import java.util.Objects;

public final class ZoneTrafficStateMachine {
    private final TrafficThresholds thresholds;
    private TrafficState currentState = new ClearTrafficState();

    public ZoneTrafficStateMachine(TrafficThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "Traffic thresholds are required");
    }

    public TrafficStatus currentStatus() {
        return currentState.status();
    }

    public TrafficStatus updateDensity(double density) {
        currentState = currentState.transition(density, thresholds);
        return currentState.status();
    }
}