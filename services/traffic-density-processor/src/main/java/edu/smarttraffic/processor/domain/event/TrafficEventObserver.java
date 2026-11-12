package edu.smarttraffic.processor.domain.event;

@FunctionalInterface
public interface TrafficEventObserver {
    void onEvent(TrafficDomainEvent event);
}