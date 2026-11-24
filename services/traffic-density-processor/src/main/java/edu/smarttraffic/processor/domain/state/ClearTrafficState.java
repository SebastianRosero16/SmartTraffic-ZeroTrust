package edu.smarttraffic.processor.domain.state;

import edu.smarttraffic.contracts.TrafficStatus;

public final class ClearTrafficState implements TrafficState {
    @Override
    public TrafficStatus status() {
        return TrafficStatus.DESPEJADA;
    }

    @Override
    public TrafficState transition(double density, TrafficThresholds thresholds) {
        TrafficStateSupport.validateTransition(density, thresholds);
        if (density >= thresholds.congestedMinimum()) {
            return new CongestedTrafficState();
        }
        if (density >= thresholds.moderateMinimum()) {
            return new ModerateTrafficState();
        }
        return this;
    }
}