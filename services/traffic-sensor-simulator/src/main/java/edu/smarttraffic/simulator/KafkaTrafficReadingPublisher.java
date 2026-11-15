package edu.smarttraffic.simulator;

import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.kafkaserdes.JsonSerde;
import edu.smarttraffic.kafkasecurity.KafkaOAuthClientConfiguration;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

public final class KafkaTrafficReadingPublisher implements AutoCloseable {
    private final String topic;
    private final Producer<String, TrafficReading> producer;

    public KafkaTrafficReadingPublisher(String topic, Producer<String, TrafficReading> producer) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Kafka topic is required");
        }
        this.topic = topic.trim();
        this.producer = Objects.requireNonNull(producer, "Kafka producer is required");
    }

    public static KafkaTrafficReadingPublisher connect(String topic, Map<String, String> environment) {
        Properties properties = KafkaOAuthClientConfiguration.fromEnvironment(environment).producerProperties();
        Producer<String, TrafficReading> producer = new KafkaProducer<>(
                properties,
                new StringSerializer(),
                new JsonSerde<>(TrafficReading.class).serializer());
        return new KafkaTrafficReadingPublisher(topic, producer);
    }

    public Future<RecordMetadata> publish(TrafficReading reading) {
        Objects.requireNonNull(reading, "Traffic reading is required");
        ProducerRecord<String, TrafficReading> record = new ProducerRecord<>(
                topic,
                null,
                reading.occurredAt().toEpochMilli(),
                reading.zoneId().value(),
                reading);
        return producer.send(record);
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(30));
    }
}
