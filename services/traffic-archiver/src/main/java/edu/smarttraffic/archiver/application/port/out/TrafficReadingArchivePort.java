package edu.smarttraffic.archiver.application.port.out;

import edu.smarttraffic.contracts.TrafficReading;

public interface TrafficReadingArchivePort {
    void archive(TrafficReading reading);
}