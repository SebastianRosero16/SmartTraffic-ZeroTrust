package edu.smarttraffic.alerts.application;

@FunctionalInterface
public interface TrafficAlertObserver {
    void onAlert(TrafficAlert alert);
}
