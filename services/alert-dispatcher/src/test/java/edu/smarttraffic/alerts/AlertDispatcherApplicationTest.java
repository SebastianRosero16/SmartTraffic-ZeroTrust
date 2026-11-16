package edu.smarttraffic.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.alerts.adapter.in.rabbitmq.RabbitMqAlertDispatcher;
import edu.smarttraffic.alerts.application.TrafficAlertPublisher;
import edu.smarttraffic.alerts.application.TrafficStateStore;
import edu.smarttraffic.alerts.config.AlertDispatcherSettings;
import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.time.Instant;
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
        classes = AlertDispatcherApplication.class,
        properties = "spring.main.lazy-initialization=true")
class AlertDispatcherApplicationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RabbitMqOAuthClient oauthClient;

    @MockitoBean
    private AlertDispatcherSettings settings;

    @MockitoBean
    private TrafficStateStore stateStore;

    @MockitoBean
    private TrafficAlertPublisher alertPublisher;

    @Test
    void startsTheRealDispatcherWithASpringManagedMapperThatHandlesInstants() throws Exception {
        CountDownLatch tokenRequestStarted = new CountDownLatch(1);
        when(oauthClient.requestAccessToken()).thenAnswer(invocation -> {
            tokenRequestStarted.countDown();
            new CountDownLatch(1).await();
            throw new AssertionError("The token request should be interrupted when the context closes");
        });

        RabbitMqAlertDispatcher dispatcher =
                applicationContext.getBean(RabbitMqAlertDispatcher.class);
        assertFalse(mockingDetails(dispatcher).isMock());
        assertNotNull(applicationContext.getBean(AlertDispatcherApplication.class));
        assertTrue(tokenRequestStarted.await(2, TimeUnit.SECONDS));
        verify(oauthClient).requestAccessToken();

        TrafficUpdate update = new TrafficUpdate(
                new ZoneId("zone-a"),
                Instant.parse("2026-11-16T12:00:00Z"),
                new TrafficMetrics(17, 8.5),
                TrafficStatus.MODERADA);
        byte[] serializedUpdate = objectMapper.writeValueAsBytes(update);
        assertEquals("2026-11-16T12:00:00Z",
                objectMapper.readTree(serializedUpdate).path("measuredAt").asText());
        assertEquals(update, objectMapper.readValue(serializedUpdate, TrafficUpdate.class));
    }
}
