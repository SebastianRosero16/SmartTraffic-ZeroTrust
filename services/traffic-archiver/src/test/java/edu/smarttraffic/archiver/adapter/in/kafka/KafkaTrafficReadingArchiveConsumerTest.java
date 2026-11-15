package edu.smarttraffic.archiver.adapter.in.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import edu.smarttraffic.archiver.application.ArchiveTrafficReadingUseCase;
import edu.smarttraffic.archiver.application.port.out.TrafficReadingArchivePort;
import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KafkaTrafficReadingArchiveConsumerTest {
    private static final String TOPIC = "traffic_readings";
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final Map<String, String> KAFKA_ENVIRONMENT = Map.of(
            "KAFKA_BOOTSTRAP_SERVERS", "localhost:9093",
            "AUTH_TOKEN_ENDPOINT_URI", "https://auth.example.test/oauth2/token",
            "KAFKA_OAUTH_CLIENT_ID", "traffic-archiver",
            "KAFKA_OAUTH_CLIENT_SECRET", "test-secret");

    @Mock
    private Consumer<String, TrafficReading> kafkaConsumer;

    @Mock
    private TrafficReadingArchivePort archivePort;

    private KafkaTrafficReadingArchiveConsumer archiveConsumer;

    @BeforeEach
    void setUp() {
        archiveConsumer = new KafkaTrafficReadingArchiveConsumer(
                TOPIC,
                kafkaConsumer,
                new ArchiveTrafficReadingUseCase(archivePort));
    }

    @Test
    void archivesRecordsAndCommitsOnlyAfterProcessingTheBatch() {
        TrafficReading reading = reading();
        when(kafkaConsumer.poll(POLL_TIMEOUT)).thenReturn(records(reading));

        archiveConsumer.subscribe();
        archiveConsumer.pollAndArchive(POLL_TIMEOUT);

        verify(kafkaConsumer).subscribe(List.of(TOPIC));
        verify(archivePort).archive(reading);
        verify(kafkaConsumer).commitSync();
    }

    @Test
    void doesNotCommitWhenArchivingFails() {
        TrafficReading reading = reading();
        when(kafkaConsumer.poll(POLL_TIMEOUT)).thenReturn(records(reading));
        doThrow(new IllegalStateException("archive failed")).when(archivePort).archive(reading);

        assertThrows(IllegalStateException.class, () -> archiveConsumer.pollAndArchive(POLL_TIMEOUT));

        verify(kafkaConsumer, never()).commitSync();
    }

    @Test
    void usesTheDedicatedDocumentedConsumerGroup() {
        Properties properties = KafkaTrafficReadingArchiveConsumer.consumerProperties(KAFKA_ENVIRONMENT);

        assertEquals("traffic-archiver", properties.get("group.id"));
        assertEquals("false", properties.get("enable.auto.commit"));
    }

    @Test
    void stopClosesConsumerWhenItHasNotStarted() {
        archiveConsumer.stop();

        verify(kafkaConsumer).close(any(CloseOptions.class));
    }

    private static TrafficReading reading() {
        return new TrafficReading(
                "event-1",
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                Instant.parse("2026-11-14T07:00:00Z"),
                12);
    }

    private static ConsumerRecords<String, TrafficReading> records(TrafficReading reading) {
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        ConsumerRecord<String, TrafficReading> record = new ConsumerRecord<>(
                TOPIC,
                partition.partition(),
                0L,
                reading.zoneId().value(),
                reading);
        return new ConsumerRecords<>(Map.of(partition, List.of(record)), Map.of());
    }
}
