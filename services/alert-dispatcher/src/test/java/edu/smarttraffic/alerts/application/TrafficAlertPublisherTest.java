package edu.smarttraffic.alerts.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrafficAlertPublisherTest {
    @Test
    void notifiesSubscribersAndStopsAfterUnsubscribe() {
        TrafficAlertPublisher publisher = new TrafficAlertPublisher();
        List<TrafficAlert> received = new ArrayList<>();
        TrafficAlertObserver observer = received::add;
        publisher.subscribe(observer);
        TrafficAlert alert = new TrafficAlert(
                new ZoneId("zone-a"),
                TrafficStatus.DESPEJADA,
                TrafficStatus.CONGESTIONADA,
                Instant.parse("2026-11-14T20:00:00Z"));

        publisher.publish(alert);
        publisher.unsubscribe(observer);
        publisher.publish(alert);

        assertEquals(List.of(alert), received);
    }
}
