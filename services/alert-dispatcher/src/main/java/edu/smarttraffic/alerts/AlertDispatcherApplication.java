package edu.smarttraffic.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.alerts.adapter.in.rabbitmq.RabbitMqAlertDispatcher;
import edu.smarttraffic.alerts.application.TrafficStateStore;
import edu.smarttraffic.alerts.config.AlertDispatcherSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.util.Map;

public final class AlertDispatcherApplication {
    private AlertDispatcherApplication() {
    }

    public static void main(String[] args) {
        Map<String, String> environment = System.getenv();
        RabbitMqAlertDispatcher dispatcher = new RabbitMqAlertDispatcher(
                new RabbitMqOAuthClient(environment),
                AlertDispatcherSettings.from(environment),
                new TrafficStateStore(),
                new ObjectMapper().findAndRegisterModules());
        Runtime.getRuntime().addShutdownHook(new Thread(dispatcher::close, "alert-dispatcher-shutdown"));
        dispatcher.run();
    }
}
