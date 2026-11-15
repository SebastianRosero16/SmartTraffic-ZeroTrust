package edu.smarttraffic.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrafficSensorSimulatorPropertiesTest {
    @Test
    void readsTopicIdentifiersAndGenerationSettingsFromEnvironment() {
        var properties = TrafficSensorSimulatorProperties.fromEnvironment(Map.of(
                "KAFKA_TRAFFIC_READINGS_TOPIC", "traffic_readings",
                "TRAFFIC_SENSOR_IDS", "sensor-1, sensor-2",
                "TRAFFIC_ZONE_IDS", "zone-1",
                "TRAFFIC_MIN_VEHICLES", "0",
                "TRAFFIC_MAX_VEHICLES", "20",
                "TRAFFIC_INTERVAL_MS", "1000"));

        assertEquals("traffic_readings", properties.topic());
        assertEquals(java.util.List.of(new SensorId("sensor-1"), new SensorId("sensor-2")), properties.sensors());
        assertEquals(java.util.List.of(new ZoneId("zone-1")), properties.zones());
        assertEquals(0, properties.minimumVehicles());
        assertEquals(20, properties.maximumVehicles());
        assertEquals(Duration.ofSeconds(1), properties.interval());
    }

    @Test
    void rejectsMissingOrInvalidEnvironmentValues() {
        assertThrows(IllegalArgumentException.class,
                () -> TrafficSensorSimulatorProperties.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> TrafficSensorSimulatorProperties.fromEnvironment(Map.of(
                        "KAFKA_TRAFFIC_READINGS_TOPIC", "traffic_readings",
                        "TRAFFIC_SENSOR_IDS", "sensor-1",
                        "TRAFFIC_ZONE_IDS", "zone-1",
                        "TRAFFIC_MIN_VEHICLES", "10",
                        "TRAFFIC_MAX_VEHICLES", "5",
                        "TRAFFIC_INTERVAL_MS", "1000")));
    }
}
