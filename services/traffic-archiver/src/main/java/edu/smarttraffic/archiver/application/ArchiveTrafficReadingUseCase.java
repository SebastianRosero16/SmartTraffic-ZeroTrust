package edu.smarttraffic.archiver.application;

import edu.smarttraffic.archiver.application.port.out.TrafficReadingArchivePort;
import edu.smarttraffic.contracts.TrafficReading;
import java.util.Objects;

public final class ArchiveTrafficReadingUseCase {
    private final TrafficReadingArchivePort archivePort;

    public ArchiveTrafficReadingUseCase(TrafficReadingArchivePort archivePort) {
        this.archivePort = Objects.requireNonNull(archivePort, "Archive port is required");
    }

    public void execute(TrafficReading reading) {
        Objects.requireNonNull(reading, "Traffic reading is required");
        archivePort.archive(reading);
    }
}