package edu.smarttraffic.queryclient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import edu.smarttraffic.queryclient.adapter.out.rabbitmq.RabbitMqTrafficQueryClient;
import edu.smarttraffic.queryclient.config.QueryClientSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = QueryClientApplication.class)
class QueryClientApplicationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private RabbitMqOAuthClient oauthClient;

    @MockitoBean
    private QueryClientSettings settings;

    @MockitoBean
    private RabbitMqTrafficQueryClient queryClient;

    @Test
    void startsAsASpringBootApplicationWithItsQueryClient() {
        assertNotNull(applicationContext.getBean(QueryClientApplication.class));
        assertNotNull(applicationContext.getBean(RabbitMqTrafficQueryClient.class));
    }
}
