package edu.smarttraffic.archiver.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "traffic_readings")
public class TrafficReadingEntity {
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "sensor_id", nullable = false, updatable = false)
    private String sensorId;

    @Column(name = "zone_id", nullable = false, updatable = false)
    private String zoneId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "vehicle_count", nullable = false, updatable = false)
    private int vehicleCount;

    protected TrafficReadingEntity() {
    }

}