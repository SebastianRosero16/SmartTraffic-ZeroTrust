package edu.smarttraffic.queryclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.smarttraffic.queryclient.adapter.out.rabbitmq.RabbitMqTrafficQueryClient;
import edu.smarttraffic.queryclient.config.QueryClientSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class QueryClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueryClientApplication.class, args);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    RabbitMqOAuthClient rabbitMqOAuthClient() {
        return new RabbitMqOAuthClient(System.getenv());
    }

    @Bean
    QueryClientSettings queryClientSettings() {
        return QueryClientSettings.from(System.getenv());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    RabbitMqTrafficQueryClient rabbitMqTrafficQueryClient(
            RabbitMqOAuthClient oauthClient,
            QueryClientSettings settings,
            ObjectMapper objectMapper) {
        return new RabbitMqTrafficQueryClient(oauthClient, settings, objectMapper);
    }
}
