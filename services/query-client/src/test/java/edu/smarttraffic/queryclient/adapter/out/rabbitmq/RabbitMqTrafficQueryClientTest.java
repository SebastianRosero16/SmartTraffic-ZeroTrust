package edu.smarttraffic.queryclient.adapter.out.rabbitmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import edu.smarttraffic.contracts.TrafficQueryResponse;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.queryclient.config.QueryClientSettings;
import edu.smarttraffic.rabbitmqsecurity.RabbitMqOAuthClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class RabbitMqTrafficQueryClientTest {
    private static final long DELIVERY_TAG = 31L;
    private static final Envelope ENVELOPE =
            new Envelope(DELIVERY_TAG, false, "query-answers-it", "client-it");

    @Test
    void completesOnlyTheRequestMatchingTheCorrelationId() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RabbitMqTrafficQueryClient client = client(mapper);
        CompletableFuture<TrafficQueryResponse> pending = client.registerPending("request-1");
        TrafficQueryResponse response = new TrafficQueryResponse("request-1", List.of());
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .correlationId("request-1")
                .build();
        Channel channel = mock(Channel.class);

        client.processResponse(channel, ENVELOPE, properties, mapper.writeValueAsBytes(response));

        assertEquals(response, pending.join());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void rejectsResponsesWithMismatchedCorrelationIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RabbitMqTrafficQueryClient client = client(mapper);
        client.registerPending("request-1");
        TrafficQueryResponse response = new TrafficQueryResponse("request-2", List.of());
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .correlationId("request-1")
                .build();
        Channel channel = mock(Channel.class);

        client.processResponse(channel, ENVELOPE, properties, mapper.writeValueAsBytes(response));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void declaresAnExclusiveAutoDeleteReplyQueueAndBindsItToTheInstanceRoute() throws Exception {
        RabbitMqTrafficQueryClient client = client(new ObjectMapper().findAndRegisterModules());
        Channel channel = mock(Channel.class);

        client.declareReplyQueue(channel);

        verify(channel).queueDeclare("query-replies-it", false, true, true, Map.of());
        verify(channel).queueBind("query-replies-it", "query-answers-it", "client-it");
    }

    private static RabbitMqTrafficQueryClient client(ObjectMapper mapper) {
        return new RabbitMqTrafficQueryClient(
                mock(RabbitMqOAuthClient.class),
                new QueryClientSettings(
                        "query-requests-it",
                        "query-answers-it",
                        "query-replies-it",
                        "client-it",
                        List.of(new ZoneId("zone-a")),
                        10_000,
                        10_000),
                mapper);
    }
}
