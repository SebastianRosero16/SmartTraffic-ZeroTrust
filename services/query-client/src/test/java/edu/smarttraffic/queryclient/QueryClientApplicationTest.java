package edu.smarttraffic.queryclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficQueryResponse;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.queryclient.adapter.out.rabbitmq.RabbitMqTrafficQueryClient;
import edu.smarttraffic.queryclient.config.QueryClientSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = QueryClientApplication.class,
        properties = "spring.main.lazy-initialization=true")
class QueryClientApplicationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RabbitMqOAuthClient oauthClient;

    @MockitoBean
    private QueryClientSettings settings;

    @Test
    void startsTheRealQueryClientWithASpringManagedMapperThatHandlesInstants() throws Exception {
        CountDownLatch tokenRequestStarted = new CountDownLatch(1);
        when(oauthClient.requestAccessToken()).thenAnswer(invocation -> {
            tokenRequestStarted.countDown();
            new CountDownLatch(1).await();
            throw new AssertionError("The token request should be interrupted when the context closes");
        });

        RabbitMqTrafficQueryClient queryClient =
                applicationContext.getBean(RabbitMqTrafficQueryClient.class);
        assertFalse(mockingDetails(queryClient).isMock());
        assertNotNull(applicationContext.getBean(QueryClientApplication.class));
        assertTrue(tokenRequestStarted.await(2, TimeUnit.SECONDS));
        verify(oauthClient).requestAccessToken();

        TrafficUpdate update = new TrafficUpdate(
                new ZoneId("zone-a"),
                Instant.parse("2026-11-16T12:00:00Z"),
                new TrafficMetrics(17, 8.5),
                TrafficStatus.MODERADA);
        TrafficQueryResponse response = new TrafficQueryResponse("request-1", List.of(update));
        byte[] serializedResponse = objectMapper.writeValueAsBytes(response);
        assertEquals("2026-11-16T12:00:00Z",
                objectMapper.readTree(serializedResponse).path("updates").get(0)
                        .path("measuredAt").asText());
        assertEquals(response, objectMapper.readValue(serializedResponse, TrafficQueryResponse.class));
    }
}
