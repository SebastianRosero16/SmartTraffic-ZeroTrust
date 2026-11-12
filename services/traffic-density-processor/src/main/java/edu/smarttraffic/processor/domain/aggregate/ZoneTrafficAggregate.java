package edu.smarttraffic.processor.domain.aggregate;

import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.processor.domain.density.DensityCalculationStrategy;
import edu.smarttraffic.processor.domain.event.CriticalDensityReachedEvent;
import edu.smarttraffic.processor.domain.event.TrafficEventObserver;
import edu.smarttraffic.processor.domain.event.TrafficStatusChangedEvent;
import edu.smarttraffic.processor.domain.state.TrafficThresholds;
import edu.smarttraffic.processor.domain.state.ZoneTrafficStateMachine;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ZoneTrafficAggregate {
    private final ZoneId zoneId;
    private final ZoneTrafficStateMachine stateMachine;
    private final List<TrafficEventObserver> observers;
    private TrafficMetrics metrics = new TrafficMetrics(0, 0);

    public ZoneTrafficAggregate(
            ZoneId zoneId,
            TrafficThresholds thresholds,
            List<TrafficEventObserver> observers) {
        this.zoneId = Objects.requireNonNull(zoneId, "Zone ID is required");
        this.stateMachine = new ZoneTrafficStateMachine(thresholds);
        this.observers = List.copyOf(Objects.requireNonNull(observers, "Observers are required"));
    }

    public TrafficStatus currentStatus() {
        return stateMachine.currentStatus();
    }

    public TrafficMetrics currentMetrics() {
        return metrics;
    }

    public TrafficStatus processReadings(
            List<TrafficReading> readings,
            DensityCalculationStrategy strategy,
            Instant evaluatedAt) {
        Objects.requireNonNull(strategy, "Density strategy is required");
        Objects.requireNonNull(readings, "Readings are required");
        if (readings.isEmpty()) {
            throw new IllegalArgumentException("At least one reading is required");
        }

        int vehicleCount = 0;
        for (TrafficReading reading : readings) {
            if (reading == null) {
                throw new IllegalArgumentException("Readings must not contain null values");
            }
            if (!zoneId.equals(reading.zoneId())) {
                throw new IllegalArgumentException("All readings must belong to the aggregate zone");
            }
            vehicleCount = Math.addExact(vehicleCount, reading.vehicleCount());
        }

        double densityValue = strategy.calculate(readings, evaluatedAt);
        TrafficMetrics nextMetrics = new TrafficMetrics(vehicleCount, densityValue);
        TrafficStatus previousStatus = stateMachine.currentStatus();
        TrafficStatus nextStatus = stateMachine.updateDensity(densityValue);
        metrics = nextMetrics;

        if (nextStatus != previousStatus) {
            notifyObservers(new TrafficStatusChangedEvent(
                    zoneId, evaluatedAt, previousStatus, nextStatus, densityValue));
            if (nextStatus == TrafficStatus.CONGESTIONADA) {
                notifyObservers(new CriticalDensityReachedEvent(zoneId, evaluatedAt, densityValue));
            }
        }

        return nextStatus;
    }

    private void notifyObservers(edu.smarttraffic.processor.domain.event.TrafficDomainEvent event) {
        observers.forEach(observer -> observer.onEvent(event));
    }
}