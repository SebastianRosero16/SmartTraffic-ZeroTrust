package edu.smarttraffic.queryclient.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryClientSettingsTest {
    @Test
    void parsesMultipleZonesAndDefaultsPollingIntervals() {
        QueryClientSettings settings = QueryClientSettings.from(Map.of(
                "RABBITMQ_QUERY_QUEUE", "query-requests-it",
                "RABBITMQ_QUERY_REPLY_QUEUE", "query-replies-it",
                "RABBITMQ_QUERY_CLIENT_ID", "client-it",
                "QUERY_ZONE_IDS", " zone-a,zone-b "));

        assertEquals(java.util.List.of("zone-a", "zone-b"),
                settings.zoneIds().stream().map(zone -> zone.value()).toList());
        assertEquals(10_000, settings.intervalMillis());
        assertEquals(10_000, settings.responseTimeoutMillis());
    }

    @Test
    void rejectsDuplicateZonesAndInvalidIntervals() {
        assertThrows(IllegalArgumentException.class, () -> QueryClientSettings.from(Map.of(
                "RABBITMQ_QUERY_QUEUE", "query-requests-it",
                "RABBITMQ_QUERY_REPLY_QUEUE", "query-replies-it",
                "RABBITMQ_QUERY_CLIENT_ID", "client-it",
                "QUERY_ZONE_IDS", "zone-a,zone-a")));
        assertThrows(IllegalArgumentException.class, () -> QueryClientSettings.from(Map.of(
                "RABBITMQ_QUERY_QUEUE", "query-requests-it",
                "RABBITMQ_QUERY_REPLY_QUEUE", "query-replies-it",
                "RABBITMQ_QUERY_CLIENT_ID", "client-it",
                "QUERY_ZONE_IDS", "zone-a",
                "QUERY_INTERVAL_SECONDS", "0")));
    }
}
