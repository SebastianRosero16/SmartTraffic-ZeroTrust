package edu.smarttraffic.processor;

import edu.smarttraffic.processor.adapter.in.kafka.KafkaStreamsTrafficDensityProcessor;
import edu.smarttraffic.processor.adapter.out.logging.TrafficEventLoggingObserver;
import edu.smarttraffic.processor.adapter.out.rabbitmq.RabbitMqOAuthTrafficUpdatePublisher;
import edu.smarttraffic.processor.domain.event.TrafficEventObserver;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TrafficDensityProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrafficDensityProcessorApplication.class, args);
    }

    @Bean
    TrafficEventObserver trafficEventLoggingObserver() {
        return new TrafficEventLoggingObserver();
    }

    @Bean(destroyMethod = "close")
    RabbitMqOAuthTrafficUpdatePublisher trafficUpdatePublisher() {
        return RabbitMqOAuthTrafficUpdatePublisher.fromEnvironment();
    }

    @Bean(destroyMethod = "close")
    KafkaStreamsTrafficDensityProcessor kafkaStreamsTrafficDensityProcessor(
            RabbitMqOAuthTrafficUpdatePublisher publisher,
            List<TrafficEventObserver> observers) {
        return KafkaStreamsTrafficDensityProcessor.fromEnvironment(
                System.getenv(), publisher, observers);
    }

    @Bean
    ApplicationRunner startTrafficDensityProcessor(KafkaStreamsTrafficDensityProcessor processor) {
        return args -> processor.start();
    }
}
