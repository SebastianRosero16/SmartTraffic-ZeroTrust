package edu.smarttraffic.simulator;

import edu.smarttraffic.contracts.TrafficReading;
import java.time.Clock;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TrafficSensorSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrafficSensorSimulatorApplication.class, args);
    }

    @Bean
    TrafficSensorSimulatorProperties simulatorProperties() {
        return TrafficSensorSimulatorProperties.fromEnvironment(System.getenv());
    }

    @Bean
    TrafficReadingGenerator trafficReadingGenerator(TrafficSensorSimulatorProperties properties) {
        return new TrafficReadingGenerator(
                properties.sensors(),
                properties.zones(),
                properties.minimumVehicles(),
                properties.maximumVehicles(),
                Clock.systemUTC(),
                new Random());
    }

    @Bean(destroyMethod = "close")
    KafkaTrafficReadingPublisher kafkaTrafficReadingPublisher(
            TrafficSensorSimulatorProperties properties) {
        return KafkaTrafficReadingPublisher.connect(properties.topic(), System.getenv());
    }

    @Bean
    ApplicationRunner publishTrafficReadings(
            TrafficReadingGenerator generator,
            KafkaTrafficReadingPublisher publisher,
            TrafficSensorSimulatorProperties properties) {
        return args -> {
            try {
                publishContinuously(generator, publisher, properties);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
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
