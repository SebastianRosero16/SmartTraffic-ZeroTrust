package edu.smarttraffic.dashboard.adapter.out.rabbitmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RabbitMqTrafficUpdateConsumerTest {
    private static final long DELIVERY_TAG = 17L;
    private static final Envelope ENVELOPE =
            new Envelope(DELIVERY_TAG, false, "traffic_updates", "zone");

    @Test
    void acknowledgesValidUpdatesAfterPassingThemToTheHandler() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        TrafficUpdate expected = new TrafficUpdate(
                new ZoneId("zone-a"),
                Instant.parse("2026-11-14T20:00:00Z"),
                new TrafficMetrics(17, 8.5),
                TrafficStatus.MODERADA);
        AtomicReference<TrafficUpdate> handled = new AtomicReference<>();
        RabbitMqTrafficUpdateConsumer consumer = consumer(objectMapper, handled::set);
        Channel channel = mock(Channel.class);

        consumer.processDelivery(channel, ENVELOPE, objectMapper.writeValueAsBytes(expected));

        assertEquals(expected, handled.get());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void rejectsMalformedMessagesWithoutRequeueing() throws Exception {
        RabbitMqTrafficUpdateConsumer consumer = consumer(new ObjectMapper(), update -> {});
        Channel channel = mock(Channel.class);

        consumer.processDelivery(
                channel,
                ENVELOPE,
                "{".getBytes(StandardCharsets.UTF_8));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void requeuesAnUpdateWhenTheHandlerFails() throws Exception {
        RabbitMqTrafficUpdateConsumer consumer = consumer(
                new ObjectMapper().findAndRegisterModules(),
                update -> {
                    throw new IllegalStateException("temporary handler failure");
                });
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        TrafficUpdate update = new TrafficUpdate(
                new ZoneId("zone-a"),
                Instant.parse("2026-11-14T20:00:00Z"),
                new TrafficMetrics(17, 8.5),
                TrafficStatus.MODERADA);
        Channel channel = mock(Channel.class);

        consumer.processDelivery(channel, ENVELOPE, objectMapper.writeValueAsBytes(update));

        verify(channel).basicNack(DELIVERY_TAG, false, true);
    }

    private static RabbitMqTrafficUpdateConsumer consumer(
            ObjectMapper objectMapper,
            java.util.function.Consumer<TrafficUpdate> updateHandler) {
        return new RabbitMqTrafficUpdateConsumer(
                mock(RabbitMqOAuthClient.class),
                "dashboard_updates_queue",
                updateHandler,
                objectMapper);
    }
}
