package edu.smarttraffic.archiver.adapter.out.persistence;

import edu.smarttraffic.archiver.application.port.out.TrafficReadingArchivePort;
import edu.smarttraffic.contracts.TrafficReading;
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTrafficReadingArchiveAdapter implements TrafficReadingArchivePort {
    private final TrafficReadingJpaRepository repository;

    public JpaTrafficReadingArchiveAdapter(TrafficReadingJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "Traffic reading repository is required");
    }

    @Override
    public void archive(TrafficReading reading) {
        Objects.requireNonNull(reading, "Traffic reading is required");
        repository.insertIfAbsent(
                reading.eventId(),
                reading.sensorId().value(),
                reading.zoneId().value(),
                reading.occurredAt(),
                reading.vehicleCount());
    }
}