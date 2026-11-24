package edu.smarttraffic.archiver.adapter.in.kafka;

import edu.smarttraffic.archiver.application.ArchiveTrafficReadingUseCase;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.kafkaserdes.JsonSerde;
import edu.smarttraffic.kafkasecurity.KafkaOAuthClientConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class KafkaTrafficReadingArchiveConsumer implements AutoCloseable {
    private static final String GROUP_ID = "traffic-archiver";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final String topic;
    private final Consumer<String, TrafficReading> consumer;
    private final ArchiveTrafficReadingUseCase archiveUseCase;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public KafkaTrafficReadingArchiveConsumer(
            String topic,
            Consumer<String, TrafficReading> consumer,
            ArchiveTrafficReadingUseCase archiveUseCase) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Kafka topic is required");
        }
        this.topic = topic.trim();
        this.consumer = Objects.requireNonNull(consumer, "Kafka consumer is required");
        this.archiveUseCase = Objects.requireNonNull(archiveUseCase, "Archive use case is required");
    }

    public static KafkaTrafficReadingArchiveConsumer connect(
            Map<String, String> environment,
            ArchiveTrafficReadingUseCase archiveUseCase) {
        Objects.requireNonNull(environment, "Environment is required");
        String topic = required(environment, "KAFKA_TRAFFIC_READINGS_TOPIC");
        Properties properties = consumerProperties(environment);
        Consumer<String, TrafficReading> consumer = new KafkaConsumer<>(
                properties,
                new StringDeserializer(),
                new JsonSerde<>(TrafficReading.class).deserializer());
        return new KafkaTrafficReadingArchiveConsumer(topic, consumer, archiveUseCase);
    }

    static Properties consumerProperties(Map<String, String> environment) {
        return KafkaOAuthClientConfiguration
                .fromEnvironment(environment)
                .consumerProperties(GROUP_ID);
    }

    public void subscribe() {
        consumer.subscribe(List.of(topic));
    }

    public void pollAndArchive(Duration timeout) {
        Objects.requireNonNull(timeout, "Poll timeout is required");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Poll timeout must not be negative");
        }

        ConsumerRecords<String, TrafficReading> records = consumer.poll(timeout);
        if (records.isEmpty()) {
            return;
        }
        records.forEach(record -> archiveUseCase.execute(record.value()));
        consumer.commitSync();
    }

    public void run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Kafka archive consumer is already running");
        }
        try {
            subscribe();
            while (!stopRequested.get() && !Thread.currentThread().isInterrupted()) {
                pollAndArchive(Duration.ofSeconds(1));
            }
        } catch (WakeupException exception) {
            if (!stopRequested.get() && !Thread.currentThread().isInterrupted()) {
                throw exception;
            }
        } finally {
            running.set(false);
            closeConsumer();
        }
    }

    public void stop() {
        stopRequested.set(true);
        if (running.get()) {
            consumer.wakeup();
        } else {
            closeConsumer();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void closeConsumer() {
        if (closed.compareAndSet(false, true)) {
            consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT));
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
