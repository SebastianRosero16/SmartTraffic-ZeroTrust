package edu.smarttraffic.alerts.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertDispatcherSettingsTest {
    @Test
    void parsesExplicitInstanceReplyQueueAllowlist() {
        AlertDispatcherSettings settings = AlertDispatcherSettings.from(Map.of(
                "RABBITMQ_ALERT_QUEUE", "alert-updates-it",
                "RABBITMQ_QUERY_QUEUE", "query-requests-it",
                "RABBITMQ_QUERY_ANSWERS_EXCHANGE", "query-answers-it",
                "RABBITMQ_QUERY_REPLY_ROUTES", "client-it=query-replies-it"));

        assertEquals("query-replies-it", settings.replyQueueFor("client-it"));
    }

    @Test
    void rejectsMalformedOrDuplicateReplyRoutes() {
        Map<String, String> environment = Map.of(
                "RABBITMQ_ALERT_QUEUE", "alert-updates-it",
                "RABBITMQ_QUERY_QUEUE", "query-requests-it",
                "RABBITMQ_QUERY_ANSWERS_EXCHANGE", "query-answers-it");

        java.util.HashMap<String, String> malformed = new java.util.HashMap<>(environment);
        malformed.put("RABBITMQ_QUERY_REPLY_ROUTES", "client-it=");
        assertThrows(IllegalArgumentException.class, () -> AlertDispatcherSettings.from(malformed));

        java.util.HashMap<String, String> duplicate = new java.util.HashMap<>(environment);
        duplicate.put("RABBITMQ_QUERY_REPLY_ROUTES", "client-it=queue-a,client-it=queue-b");
        assertThrows(IllegalArgumentException.class, () -> AlertDispatcherSettings.from(duplicate));
    }
}
