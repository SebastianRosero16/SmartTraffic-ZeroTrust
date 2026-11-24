package edu.smarttraffic.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.alerts.adapter.in.rabbitmq.RabbitMqAlertDispatcher;
import edu.smarttraffic.alerts.application.TrafficStateStore;
import edu.smarttraffic.alerts.config.AlertDispatcherSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AlertDispatcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlertDispatcherApplication.class, args);
    }

    @Bean
    RabbitMqOAuthClient rabbitMqOAuthClient() {
        return new RabbitMqOAuthClient(System.getenv());
    }

    @Bean
    AlertDispatcherSettings alertDispatcherSettings() {
        return AlertDispatcherSettings.from(System.getenv());
    }

    @Bean
    TrafficStateStore trafficStateStore() {
        return new TrafficStateStore();
    }

    @Bean
    ObjectMapper alertDispatcherObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(destroyMethod = "close")
    RabbitMqAlertDispatcher rabbitMqAlertDispatcher(
            RabbitMqOAuthClient oauthClient,
            AlertDispatcherSettings settings,
            TrafficStateStore stateStore,
            ObjectMapper objectMapper) {
        return new RabbitMqAlertDispatcher(
                oauthClient,
                settings,
                stateStore,
                objectMapper);
    }

    @Bean
    ApplicationRunner runAlertDispatcher(RabbitMqAlertDispatcher dispatcher) {
        return args -> dispatcher.run();
    }
}
