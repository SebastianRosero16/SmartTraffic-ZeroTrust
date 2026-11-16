package edu.smarttraffic.alerts;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import edu.smarttraffic.alerts.adapter.in.rabbitmq.RabbitMqAlertDispatcher;
import edu.smarttraffic.alerts.application.TrafficAlertPublisher;
import edu.smarttraffic.alerts.application.TrafficStateStore;
import edu.smarttraffic.alerts.config.AlertDispatcherSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = AlertDispatcherApplication.class)
class AlertDispatcherApplicationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private RabbitMqOAuthClient oauthClient;

    @MockitoBean
    private AlertDispatcherSettings settings;

    @MockitoBean
    private TrafficStateStore stateStore;

    @MockitoBean
    private TrafficAlertPublisher alertPublisher;

    @MockitoBean
    private RabbitMqAlertDispatcher dispatcher;

    @Test
    void startsAsASpringBootApplicationWithItsDispatcher() {
        assertNotNull(applicationContext.getBean(AlertDispatcherApplication.class));
        assertNotNull(applicationContext.getBean(RabbitMqAlertDispatcher.class));
    }
}
