package edu.smarttraffic.processor.adapter.in.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.kafkaserdes.JsonSerde;
import edu.smarttraffic.processor.domain.event.CriticalDensityReachedEvent;
import edu.smarttraffic.processor.domain.event.TrafficDomainEvent;
import edu.smarttraffic.processor.domain.event.TrafficStatusChangedEvent;
import edu.smarttraffic.processor.domain.state.TrafficThresholds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;

class TrafficDensityTopologyTest {
    private static final String INPUT_TOPIC = "traffic_readings";
    private static final Instant BASE_TIME = Instant.parse("2026-11-14T07:00:00Z");

    @Test
    void publishesRunningAverageAndStatusForEachZoneWindow() {
        List<TrafficUpdate> updates = new ArrayList<>();
        var topology = new TrafficDensityTopology(
                INPUT_TOPIC,
                Duration.ofSeconds(60),
                Duration.ZERO,
                new TrafficThresholds(5, 10),
                updates::add);
        Properties properties = testProperties();

        try (var driver = new TopologyTestDriver(topology.build(), properties)) {
            TestInputTopic<String, TrafficReading> input = driver.createInputTopic(
                    INPUT_TOPIC,
                    Serdes.String().serializer(),
                    new JsonSerde<>(TrafficReading.class).serializer());

            input.pipeInput(
                    "not-the-zone-key",
                    reading("event-1", "zone-1", 4, BASE_TIME),
                    BASE_TIME.toEpochMilli());
            input.pipeInput(
                    "still-not-the-zone-key",
                    reading("event-2", "zone-1", 6, BASE_TIME.plusSeconds(1)),
                    BASE_TIME.plusSeconds(1).toEpochMilli());

            assertEquals(2, updates.size());
            TrafficUpdate latest = updates.get(1);
            assertEquals(new ZoneId("zone-1"), latest.zoneId());
            assertEquals(BASE_TIME.plusSeconds(1), latest.measuredAt());
            assertEquals(10, latest.metrics().vehicleCount());
            assertEquals(5.0, latest.metrics().densityValue());
            assertEquals(TrafficStatus.MODERADA, latest.status());
        }
    }

    @Test
    void startsANewAggregateAtTheNextWindowBoundary() {
        List<TrafficUpdate> updates = new ArrayList<>();
        var topology = new TrafficDensityTopology(
                INPUT_TOPIC,
                Duration.ofSeconds(60),
                Duration.ZERO,
                new TrafficThresholds(5, 10),
                updates::add);

        try (var driver = new TopologyTestDriver(topology.build(), testProperties())) {
            TestInputTopic<String, TrafficReading> input = driver.createInputTopic(
                    INPUT_TOPIC,
                    Serdes.String().serializer(),
                    new JsonSerde<>(TrafficReading.class).serializer());

            input.pipeInput(
                    "zone-1",
                    reading("event-1", "zone-1", 12, BASE_TIME),
                    BASE_TIME.toEpochMilli());
            input.pipeInput(
                    "zone-1",
                    reading("event-2", "zone-1", 2, BASE_TIME.plusSeconds(60)),
                    BASE_TIME.plusSeconds(60).toEpochMilli());

            assertEquals(2, updates.size());
            assertEquals(12, updates.get(0).metrics().vehicleCount());
            assertEquals(TrafficStatus.CONGESTIONADA, updates.get(0).status());
            assertEquals(2, updates.get(1).metrics().vehicleCount());
            assertEquals(TrafficStatus.DESPEJADA, updates.get(1).status());
        }
    }

    @Test
    void lateUpdateFromOlderWindowDoesNotReplaceNewerZoneStatus() {
        List<TrafficUpdate> updates = new ArrayList<>();
        var topology = new TrafficDensityTopology(
                INPUT_TOPIC,
                Duration.ofSeconds(60),
                Duration.ofSeconds(120),
                new TrafficThresholds(5, 10),
                updates::add);

        try (var driver = new TopologyTestDriver(topology.build(), testProperties())) {
            TestInputTopic<String, TrafficReading> input = driver.createInputTopic(
                    INPUT_TOPIC,
                    Serdes.String().serializer(),
                    new JsonSerde<>(TrafficReading.class).serializer());

            input.pipeInput(
                    "zone-1",
                    reading("event-1", "zone-1", 12, BASE_TIME),
                    BASE_TIME.toEpochMilli());
            input.pipeInput(
                    "zone-1",
                    reading("event-2", "zone-1", 2, BASE_TIME.plusSeconds(60)),
                    BASE_TIME.plusSeconds(60).toEpochMilli());
            input.pipeInput(
                    "zone-1",
                    reading("event-3", "zone-1", 2, BASE_TIME.plusSeconds(30)),
                    BASE_TIME.plusSeconds(30).toEpochMilli());

            assertEquals(2, updates.size());
            assertEquals(TrafficStatus.DESPEJADA, updates.get(1).status());
            assertEquals(BASE_TIME.plusSeconds(60), updates.get(1).measuredAt());
        }
    }

    @Test
    void preservesZoneStateAcrossWindowsAndNotifiesObserversOnTransitions() {
        List<TrafficUpdate> updates = new ArrayList<>();
        List<TrafficDomainEvent> events = new ArrayList<>();
        var topology = new TrafficDensityTopology(
                INPUT_TOPIC,
                Duration.ofSeconds(60),
                Duration.ZERO,
                new TrafficThresholds(5, 10),
                updates::add,
                List.of(events::add));

        try (var driver = new TopologyTestDriver(topology.build(), testProperties())) {
            TestInputTopic<String, TrafficReading> input = driver.createInputTopic(
                    INPUT_TOPIC,
                    Serdes.String().serializer(),
                    new JsonSerde<>(TrafficReading.class).serializer());

            input.pipeInput("zone-1", reading("event-1", "zone-1", 12), BASE_TIME.toEpochMilli());
            input.pipeInput(
                    "zone-1",
                    reading("event-2", "zone-1", 12, BASE_TIME.plusSeconds(60)),
                    BASE_TIME.plusSeconds(60).toEpochMilli());
            input.pipeInput(
                    "zone-1",
                    reading("event-3", "zone-1", 7, BASE_TIME.plusSeconds(120)),
                    BASE_TIME.plusSeconds(120).toEpochMilli());
            input.pipeInput(
                    "zone-1",
                    reading("event-4", "zone-1", 3, BASE_TIME.plusSeconds(180)),
                    BASE_TIME.plusSeconds(180).toEpochMilli());

            assertEquals(
                    List.of(
                            TrafficStatus.CONGESTIONADA,
                            TrafficStatus.CONGESTIONADA,
                            TrafficStatus.MODERADA,
                            TrafficStatus.DESPEJADA),
                    updates.stream().map(TrafficUpdate::status).toList());
            assertEquals(4, events.size());
            assertEquals(3, events.stream().filter(TrafficStatusChangedEvent.class::isInstance).count());
            assertEquals(1, events.stream().filter(CriticalDensityReachedEvent.class::isInstance).count());
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficDensityTopology(
                " ",
                Duration.ofSeconds(60),
                Duration.ZERO,
                new TrafficThresholds(5, 10),
                update -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> new TrafficDensityTopology(
                INPUT_TOPIC,
                Duration.ZERO,
                Duration.ZERO,
                new TrafficThresholds(5, 10),
                update -> {
                }));
        assertThrows(NullPointerException.class, () -> new TrafficDensityTopology(
                INPUT_TOPIC,
                Duration.ofSeconds(60),
                Duration.ZERO,
                null,
                update -> {
                }));
    }

    private static TrafficReading reading(String eventId, String zoneId, int vehicleCount) {
        return reading(eventId, zoneId, vehicleCount, BASE_TIME);
    }

    private static TrafficReading reading(
            String eventId, String zoneId, int vehicleCount, Instant occurredAt) {
        return new TrafficReading(
                eventId,
                new SensorId("sensor-1"),
                new ZoneId(zoneId),
                occurredAt,
                vehicleCount);
    }

    private static Properties testProperties() {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "traffic-density-topology-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        return properties;
    }
}
