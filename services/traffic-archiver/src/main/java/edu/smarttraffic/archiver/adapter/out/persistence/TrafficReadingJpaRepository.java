package edu.smarttraffic.archiver.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TrafficReadingJpaRepository extends JpaRepository<TrafficReadingEntity, String> {
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO traffic_readings (event_id, sensor_id, zone_id, occurred_at, vehicle_count)
            VALUES (:eventId, :sensorId, :zoneId, :occurredAt, :vehicleCount)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("sensorId") String sensorId,
            @Param("zoneId") String zoneId,
            @Param("occurredAt") java.time.Instant occurredAt,
            @Param("vehicleCount") int vehicleCount);
}