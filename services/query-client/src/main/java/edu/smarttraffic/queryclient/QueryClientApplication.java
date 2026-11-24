package edu.smarttraffic.queryclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.queryclient.adapter.out.rabbitmq.RabbitMqTrafficQueryClient;
import edu.smarttraffic.queryclient.config.QueryClientSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class QueryClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueryClientApplication.class, args);
    }

    @Bean
    RabbitMqOAuthClient rabbitMqOAuthClient() {
        return new RabbitMqOAuthClient(System.getenv());
    }

    @Bean
    QueryClientSettings queryClientSettings() {
        return QueryClientSettings.from(System.getenv());
    }

    @Bean
    ObjectMapper queryClientObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(destroyMethod = "close")
    RabbitMqTrafficQueryClient rabbitMqTrafficQueryClient(
            RabbitMqOAuthClient oauthClient,
            QueryClientSettings settings,
            ObjectMapper objectMapper) {
        return new RabbitMqTrafficQueryClient(
                oauthClient,
                settings,
                objectMapper);
    }

    @Bean
    ApplicationRunner runQueryClient(RabbitMqTrafficQueryClient queryClient) {
        return args -> queryClient.run();
    }
}
