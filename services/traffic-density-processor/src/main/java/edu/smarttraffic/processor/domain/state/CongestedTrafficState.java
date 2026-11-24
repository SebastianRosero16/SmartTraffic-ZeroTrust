package edu.smarttraffic.processor.domain.state;

import edu.smarttraffic.contracts.TrafficStatus;

public final class CongestedTrafficState implements TrafficState {
    @Override
    public TrafficStatus status() {
        return TrafficStatus.CONGESTIONADA;
    }

    @Override
    public TrafficState transition(double density, TrafficThresholds thresholds) {
        TrafficStateSupport.validateTransition(density, thresholds);
        if (density < thresholds.moderateMinimum()) {
            return new ClearTrafficState();
        }
        if (density < thresholds.congestedMinimum()) {
            return new ModerateTrafficState();
        }
        return this;
    }
}