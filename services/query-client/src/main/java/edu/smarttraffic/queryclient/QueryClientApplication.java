package edu.smarttraffic.queryclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.queryclient.adapter.out.rabbitmq.RabbitMqTrafficQueryClient;
import edu.smarttraffic.queryclient.config.QueryClientSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.util.Map;

public final class QueryClientApplication {
    private QueryClientApplication() {
    }

    public static void main(String[] args) {
        Map<String, String> environment = System.getenv();
        RabbitMqTrafficQueryClient queryClient = new RabbitMqTrafficQueryClient(
                new RabbitMqOAuthClient(environment),
                QueryClientSettings.from(environment),
                new ObjectMapper().findAndRegisterModules());
        Runtime.getRuntime().addShutdownHook(new Thread(queryClient::close, "query-client-shutdown"));
        queryClient.run();
    }
}
