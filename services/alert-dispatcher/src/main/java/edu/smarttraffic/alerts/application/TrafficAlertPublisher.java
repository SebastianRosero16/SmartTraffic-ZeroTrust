package edu.smarttraffic.alerts.application;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TrafficAlertPublisher {
    private final CopyOnWriteArrayList<TrafficAlertObserver> observers = new CopyOnWriteArrayList<>();

    public void subscribe(TrafficAlertObserver observer) {
        observers.addIfAbsent(Objects.requireNonNull(observer, "Traffic alert observer is required"));
    }

    public void unsubscribe(TrafficAlertObserver observer) {
        observers.remove(Objects.requireNonNull(observer, "Traffic alert observer is required"));
    }

    public void publish(TrafficAlert alert) {
        Objects.requireNonNull(alert, "Traffic alert is required");
        observers.forEach(observer -> observer.onAlert(alert));
    }
}
