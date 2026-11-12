package edu.smarttraffic.archiver.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import edu.smarttraffic.archiver.application.port.out.TrafficReadingArchivePort;
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
class ArchiveTrafficReadingUseCaseTest {
    @Mock
    private TrafficReadingArchivePort archivePort;

    @InjectMocks
    private ArchiveTrafficReadingUseCase useCase;

    @Test
    void archivesValidReadingThroughPort() {
        var reading = new TrafficReading(
                "event-1",
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                Instant.EPOCH,
                12);

        useCase.execute(reading);

        verify(archivePort).archive(reading);
    }

    @Test
    void rejectsNullReadingWithoutCallingPort() {
        assertThrows(NullPointerException.class, () -> useCase.execute(null));

        verifyNoInteractions(archivePort);
    }
}