package edu.smarttraffic.processor.adapter.in.kafka;

import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.kafkaserdes.JsonSerde;
import edu.smarttraffic.processor.application.port.out.TrafficUpdatePublisher;
import edu.smarttraffic.processor.domain.aggregate.ZoneTrafficAggregate;
import edu.smarttraffic.processor.domain.density.AverageDensityStrategy;
import edu.smarttraffic.processor.domain.density.DensityCalculationStrategy;
import edu.smarttraffic.processor.domain.event.TrafficEventObserver;
import edu.smarttraffic.processor.domain.state.TrafficThresholds;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

public final class TrafficDensityTopology {
    private static final String STATE_STORE_NAME = "traffic-density-by-zone";
    private static final String STATUS_STORE_NAME = "traffic-status-by-zone";
    private static final String STATUS_WINDOW_START_STORE_NAME =
            "traffic-status-window-start-by-zone";

    private final String readingsTopic;
    private final Duration windowSize;
    private final Duration windowGrace;
    private final TrafficThresholds thresholds;
    private final TrafficUpdatePublisher updatePublisher;
    private final DensityCalculationStrategy densityStrategy;
    private final List<TrafficEventObserver> observers;
    private final JsonSerde<TrafficReading> readingSerde = new JsonSerde<>(TrafficReading.class);
    private final JsonSerde<ZoneWindowAggregate> aggregateSerde = new JsonSerde<>(ZoneWindowAggregate.class);

    public TrafficDensityTopology(
            String readingsTopic,
            Duration windowSize,
            Duration windowGrace,
            TrafficThresholds thresholds,
            TrafficUpdatePublisher updatePublisher) {
        this(
                readingsTopic,
                windowSize,
                windowGrace,
                thresholds,
                updatePublisher,
                new AverageDensityStrategy(),
                List.of());
    }

    public TrafficDensityTopology(
            String readingsTopic,
            Duration windowSize,
            Duration windowGrace,
            TrafficThresholds thresholds,
            TrafficUpdatePublisher updatePublisher,
            List<TrafficEventObserver> observers) {
        this(
                readingsTopic,
                windowSize,
                windowGrace,
                thresholds,
                updatePublisher,
                new AverageDensityStrategy(),
                observers);
    }

    public TrafficDensityTopology(
            String readingsTopic,
            Duration windowSize,
            Duration windowGrace,
            TrafficThresholds thresholds,
            TrafficUpdatePublisher updatePublisher,
            DensityCalculationStrategy densityStrategy,
            List<TrafficEventObserver> observers) {
        if (readingsTopic == null || readingsTopic.isBlank()) {
            throw new IllegalArgumentException("Readings topic is required");
        }
        if (windowSize == null || windowSize.isZero() || windowSize.isNegative()) {
            throw new IllegalArgumentException("Window size must be at least one millisecond");
        }
        try {
            if (windowSize.toMillis() == 0) {
                throw new IllegalArgumentException("Window size must be at least one millisecond");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Window size is too large", exception);
        }
        if (windowGrace == null || windowGrace.isNegative()) {
            throw new IllegalArgumentException("Window grace must not be negative");
        }
        try {
            windowGrace.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Window grace is too large", exception);
        }
        this.readingsTopic = readingsTopic.trim();
        this.windowSize = windowSize;
        this.windowGrace = windowGrace;
        this.thresholds = Objects.requireNonNull(thresholds, "Traffic thresholds are required");
        this.updatePublisher = Objects.requireNonNull(updatePublisher, "Traffic update publisher is required");
        this.densityStrategy = Objects.requireNonNull(densityStrategy, "Density strategy is required");
        this.observers = List.copyOf(Objects.requireNonNull(observers, "Traffic observers are required"));
    }

    public Topology build() {
        StreamsBuilder builder = new StreamsBuilder();
        Serde<String> zoneIdSerde = Serdes.String();

        KTable<Windowed<String>, ZoneWindowAggregate> aggregates = builder
                .stream(readingsTopic, Consumed.with(zoneIdSerde, readingSerde))
                .groupBy(
                        (key, reading) -> {
                            Objects.requireNonNull(reading, "Traffic reading is required");
                            return reading.zoneId().value();
                        },
                        Grouped.with(zoneIdSerde, readingSerde))
                .windowedBy(TimeWindows.ofSizeAndGrace(windowSize, windowGrace))
                .aggregate(
                        ZoneWindowAggregate::empty,
                        (zoneId, reading, aggregate) -> aggregate.add(reading),
                        Materialized.<String, ZoneWindowAggregate, WindowStore<Bytes, byte[]>>as(
                                        STATE_STORE_NAME)
                                .withKeySerde(zoneIdSerde)
                                .withValueSerde(aggregateSerde));

        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATUS_STORE_NAME),
                zoneIdSerde,
                Serdes.String()));
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATUS_WINDOW_START_STORE_NAME),
                zoneIdSerde,
                Serdes.Long()));

        KStream<Windowed<String>, ZoneWindowAggregate> updates = aggregates.toStream();
        updates.process(
                () -> new Processor<Windowed<String>, ZoneWindowAggregate, Void, Void>() {
                    private KeyValueStore<String, String> statusStore;
                    private KeyValueStore<String, Long> statusWindowStartStore;

                    @Override
                    public void init(ProcessorContext<Void, Void> context) {
                        statusStore = context.getStateStore(STATUS_STORE_NAME);
                        statusWindowStartStore = context.getStateStore(STATUS_WINDOW_START_STORE_NAME);
                    }

                    @Override
                    public void process(Record<Windowed<String>, ZoneWindowAggregate> record) {
                        publishUpdate(record.key(), record.value(), statusStore, statusWindowStartStore);
                    }
                },
                Named.as("publish-traffic-updates"),
                        STATUS_STORE_NAME,
                        STATUS_WINDOW_START_STORE_NAME);

        return builder.build();
    }

    private void publishUpdate(
            Windowed<String> windowedZoneId,
            ZoneWindowAggregate aggregate,
                KeyValueStore<String, String> statusStore,
                KeyValueStore<String, Long> statusWindowStartStore) {
        String zoneIdValue = windowedZoneId.key();
        long windowStart = windowedZoneId.window().start();
        Long latestWindowStart = statusWindowStartStore.get(zoneIdValue);
        if (latestWindowStart != null && windowStart < latestWindowStart) {
            return;
        }

        String storedStatus = statusStore.get(zoneIdValue);
        TrafficStatus initialStatus = storedStatus == null
                ? TrafficStatus.DESPEJADA
                : TrafficStatus.valueOf(storedStatus);
        ZoneTrafficAggregate domainAggregate = new ZoneTrafficAggregate(
                new ZoneId(zoneIdValue),
                thresholds,
                observers,
                initialStatus);
        double density = densityStrategy.calculateWindow(aggregate.densityStatistics());
        TrafficStatus status = domainAggregate.processAggregate(
                Math.toIntExact(aggregate.totalVehicleCount()),
                density,
                aggregate.latestOccurredAt());
        statusStore.put(zoneIdValue, status.name());
        statusWindowStartStore.put(zoneIdValue, windowStart);
        TrafficUpdate update = new TrafficUpdate(
                new ZoneId(zoneIdValue),
                aggregate.latestOccurredAt(),
                domainAggregate.currentMetrics(),
                status);
        updatePublisher.publish(update);
    }
}
