package edu.smarttraffic.archiver.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaTrafficReadingArchiveAdapterTest {
    @Mock
    private TrafficReadingJpaRepository repository;

    @InjectMocks
    private JpaTrafficReadingArchiveAdapter adapter;

    @Test
    void mapsReadingFieldsToIdempotentInsert() {
        var occurredAt = Instant.parse("2026-11-11T12:00:00Z");
        var reading = new TrafficReading(
                "event-1",
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                occurredAt,
                12);

        adapter.archive(reading);

        verify(repository).insertIfAbsent("event-1", "sensor-1", "zone-1", occurredAt, 12);
    }

    @Test
    void rejectsNullReadingWithoutCallingRepository() {
        assertThrows(NullPointerException.class, () -> adapter.archive(null));

        verifyNoInteractions(repository);
    }
}