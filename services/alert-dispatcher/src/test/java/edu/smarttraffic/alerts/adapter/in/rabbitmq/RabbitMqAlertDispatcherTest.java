package edu.smarttraffic.alerts.adapter.in.rabbitmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import edu.smarttraffic.alerts.application.TrafficStateStore;
import edu.smarttraffic.alerts.application.TrafficAlertPublisher;
import edu.smarttraffic.alerts.application.TrafficAlert;
import edu.smarttraffic.alerts.config.AlertDispatcherSettings;
import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficQuery;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RabbitMqAlertDispatcherTest {
    private static final long DELIVERY_TAG = 23L;
    private static final Envelope ENVELOPE =
            new Envelope(DELIVERY_TAG, false, "query-requests-it", "");

    @Test
    void answersQueriesFromTheLocalSnapshotAndPreservesCorrelation() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TrafficStateStore store = new TrafficStateStore();
        TrafficUpdate update = new TrafficUpdate(
                new ZoneId("zone-a"),
                Instant.parse("2026-11-14T20:00:00Z"),
                new TrafficMetrics(17, 8.5),
                TrafficStatus.MODERADA);
        store.accept(update);
        RabbitMqAlertDispatcher dispatcher = dispatcher(store, mapper);
        Channel channel = mock(Channel.class);
        TrafficQuery query = new TrafficQuery("request-1", "client-it", java.util.List.of(new ZoneId("zone-a")));
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .correlationId("request-1")
                .replyTo("query-replies-it")
                .build();

        dispatcher.processQuery(channel, ENVELOPE, properties, mapper.writeValueAsBytes(query));

        ArgumentCaptor<AMQP.BasicProperties> propertiesCaptor =
                ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(channel).basicPublish(
                eq("query-answers-it"),
                eq("client-it"),
                eq(true),
                propertiesCaptor.capture(),
                bodyCaptor.capture());
        assertEquals("request-1", propertiesCaptor.getValue().getCorrelationId());
        assertEquals(update, mapper.readValue(
                bodyCaptor.getValue(),
                edu.smarttraffic.contracts.TrafficQueryResponse.class).updates().get(0));
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void rejectsQueriesWithAnUnauthorizedReplyQueue() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RabbitMqAlertDispatcher dispatcher = dispatcher(new TrafficStateStore(), mapper);
        Channel channel = mock(Channel.class);
        TrafficQuery query = new TrafficQuery("request-1", "client-it", java.util.List.of(new ZoneId("zone-a")));
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .correlationId("request-1")
                .replyTo("other-replies")
                .build();

        dispatcher.processQuery(channel, ENVELOPE, properties, mapper.writeValueAsBytes(query));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void publishesAlertToObserversWhenTrafficStatusChanges() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TrafficAlertPublisher publisher = new TrafficAlertPublisher();
        AtomicReference<TrafficAlert> observed = new AtomicReference<>();
        publisher.subscribe(observed::set);
        RabbitMqAlertDispatcher dispatcher = dispatcher(new TrafficStateStore(), publisher, mapper);
        TrafficUpdate update = new TrafficUpdate(
                new ZoneId("zone-a"),
                Instant.parse("2026-11-14T20:00:00Z"),
                new TrafficMetrics(17, 8.5),
                TrafficStatus.MODERADA);
        Channel channel = mock(Channel.class);

        dispatcher.processUpdate(channel, ENVELOPE, mapper.writeValueAsBytes(update));

        assertEquals(new TrafficAlert(
                new ZoneId("zone-a"),
                TrafficStatus.DESPEJADA,
                TrafficStatus.MODERADA,
                update.measuredAt()), observed.get());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    private static RabbitMqAlertDispatcher dispatcher(TrafficStateStore store, ObjectMapper mapper) {
        return dispatcher(store, new TrafficAlertPublisher(), mapper);
    }

    private static RabbitMqAlertDispatcher dispatcher(
            TrafficStateStore store,
            TrafficAlertPublisher publisher,
            ObjectMapper mapper) {
        return new RabbitMqAlertDispatcher(
                mock(RabbitMqOAuthClient.class),
                new AlertDispatcherSettings(
                        "alert-updates-it",
                        "query-requests-it",
                        "query-answers-it",
                        Map.of("client-it", "query-replies-it")),
                store,
                publisher,
                mapper);
    }
}
