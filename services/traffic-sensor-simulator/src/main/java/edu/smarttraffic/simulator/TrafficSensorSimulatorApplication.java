package edu.smarttraffic.simulator;

import edu.smarttraffic.contracts.TrafficReading;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.random.RandomGenerator;
import org.apache.kafka.clients.producer.RecordMetadata;

public final class TrafficSensorSimulatorApplication {
    private TrafficSensorSimulatorApplication() {
    }

    public static void main(String[] args) throws ExecutionException {
        Map<String, String> environment = System.getenv();
        TrafficSensorSimulatorProperties properties =
                TrafficSensorSimulatorProperties.fromEnvironment(environment);
        TrafficReadingGenerator generator = new TrafficReadingGenerator(
                properties.sensors(),
                properties.zones(),
                properties.minimumVehicles(),
                properties.maximumVehicles(),
                Clock.systemUTC(),
                RandomGenerator.getDefault());

        try (KafkaTrafficReadingPublisher publisher =
                KafkaTrafficReadingPublisher.connect(properties.topic(), environment)) {
            publishContinuously(generator, publisher, properties);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void publishContinuously(
            TrafficReadingGenerator generator,
            KafkaTrafficReadingPublisher publisher,
            TrafficSensorSimulatorProperties properties) throws ExecutionException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            TrafficReading reading = generator.generate();
            RecordMetadata metadata = publisher.publish(reading).get();
            System.out.printf(
                    "Published event %s to %s partition %d offset %d%n",
                    reading.eventId(),
                    metadata.topic(),
                    metadata.partition(),
                    metadata.offset());
            Thread.sleep(properties.interval().toMillis());
        }
    }
}
