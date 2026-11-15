package edu.smarttraffic.dashboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardSettingsTest {
    @Test
    void usesTheProvisionalDashboardQueueByDefault() {
        assertEquals(
                DashboardSettings.DEFAULT_QUEUE,
                DashboardSettings.from(Map.of()).queue());
    }

    @Test
    void rejectsBlankConfiguredQueue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DashboardSettings.from(Map.of("RABBITMQ_DASHBOARD_QUEUE", "  ")));
    }
}
