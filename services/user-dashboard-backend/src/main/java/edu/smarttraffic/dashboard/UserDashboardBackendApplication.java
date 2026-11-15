package edu.smarttraffic.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.dashboard.adapter.in.websocket.TrafficUpdateWebSocketHandler;
import edu.smarttraffic.dashboard.adapter.out.rabbitmq.RabbitMqTrafficUpdateConsumer;
import edu.smarttraffic.dashboard.application.TrafficUpdateStore;
import edu.smarttraffic.dashboard.config.DashboardSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class UserDashboardBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserDashboardBackendApplication.class, args);
    }

    @Bean
    RabbitMqOAuthClient rabbitMqOAuthClient() {
        return new RabbitMqOAuthClient(System.getenv());
    }

    @Bean
    DashboardSettings dashboardSettings() {
        return DashboardSettings.from(System.getenv());
    }

    @Bean
    TrafficUpdateStore trafficUpdateStore() {
        return new TrafficUpdateStore();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    RabbitMqTrafficUpdateConsumer rabbitMqTrafficUpdateConsumer(
            RabbitMqOAuthClient oauthClient,
            DashboardSettings settings,
            TrafficUpdateWebSocketHandler webSocketHandler,
            ObjectMapper objectMapper) {
        return new RabbitMqTrafficUpdateConsumer(
                oauthClient,
                settings.queue(),
                webSocketHandler::accept,
                objectMapper);
    }
}
