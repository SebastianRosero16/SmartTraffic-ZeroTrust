package edu.smarttraffic.processor.application.port.out;

import edu.smarttraffic.contracts.TrafficUpdate;

@FunctionalInterface
public interface TrafficUpdatePublisher {
    void publish(TrafficUpdate update);
}
