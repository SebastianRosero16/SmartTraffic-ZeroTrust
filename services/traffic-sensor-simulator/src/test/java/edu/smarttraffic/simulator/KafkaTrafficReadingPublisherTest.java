package edu.smarttraffic.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KafkaTrafficReadingPublisherTest {
    @Test
    void publishesReadingUsingZoneKeyAndOccurrenceTimestamp() {
        Producer<String, TrafficReading> kafkaProducer = mock();
        Future<RecordMetadata> result = CompletableFuture.completedFuture(null);
        when(kafkaProducer.send(any())).thenReturn(result);
        var publisher = new KafkaTrafficReadingPublisher("traffic_readings", kafkaProducer);
        var reading = new TrafficReading(
                "event-1",
                new SensorId("sensor-1"),
                new ZoneId("zone-1"),
                Instant.parse("2026-11-14T07:00:00Z"),
                12);

        Future<RecordMetadata> published = publisher.publish(reading);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, TrafficReading>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaProducer).send(recordCaptor.capture());
        ProducerRecord<String, TrafficReading> record = recordCaptor.getValue();
        assertEquals("traffic_readings", record.topic());
        assertEquals("zone-1", record.key());
        assertEquals(reading.occurredAt().toEpochMilli(), record.timestamp());
        assertEquals(reading, record.value());
        assertEquals(result, published);
    }

    @Test
    void rejectsBlankTopicAndNullReading() {
        Producer<String, TrafficReading> kafkaProducer = mock();
        assertThrows(IllegalArgumentException.class,
                () -> new KafkaTrafficReadingPublisher(" ", kafkaProducer));

        var publisher = new KafkaTrafficReadingPublisher("traffic_readings", kafkaProducer);
        assertThrows(NullPointerException.class, () -> publisher.publish(null));
    }
}
