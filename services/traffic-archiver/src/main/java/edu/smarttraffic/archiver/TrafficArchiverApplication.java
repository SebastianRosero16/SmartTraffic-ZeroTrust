package edu.smarttraffic.archiver;

import edu.smarttraffic.archiver.adapter.in.kafka.KafkaTrafficReadingArchiveConsumer;
import edu.smarttraffic.archiver.application.ArchiveTrafficReadingUseCase;
import edu.smarttraffic.archiver.application.port.out.TrafficReadingArchivePort;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TrafficArchiverApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrafficArchiverApplication.class, args);
    }

    @Bean
    ArchiveTrafficReadingUseCase archiveTrafficReadingUseCase(TrafficReadingArchivePort archivePort) {
        return new ArchiveTrafficReadingUseCase(archivePort);
    }

    @Bean(destroyMethod = "stop")
    KafkaTrafficReadingArchiveConsumer kafkaTrafficReadingArchiveConsumer(
            ArchiveTrafficReadingUseCase archiveUseCase) {
        return KafkaTrafficReadingArchiveConsumer.connect(System.getenv(), archiveUseCase);
    }

    @Bean
    ApplicationRunner archiveKafkaReadings(KafkaTrafficReadingArchiveConsumer consumer) {
        return args -> consumer.run();
    }
}
