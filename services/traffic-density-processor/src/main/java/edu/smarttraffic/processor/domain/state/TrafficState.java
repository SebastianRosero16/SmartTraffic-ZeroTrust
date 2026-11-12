package edu.smarttraffic.processor.domain.state;

import edu.smarttraffic.contracts.TrafficStatus;

public interface TrafficState {
    TrafficStatus status();

    TrafficState transition(double density, TrafficThresholds thresholds);
}