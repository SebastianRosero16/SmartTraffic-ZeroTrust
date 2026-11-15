package edu.smarttraffic.dashboard.adapter.in.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.contracts.TrafficMetrics;
import edu.smarttraffic.contracts.TrafficStatus;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.contracts.ZoneId;
import edu.smarttraffic.dashboard.application.TrafficUpdateStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class TrafficUpdateWebSocketHandlerTest {
    @Test
    void broadcastsLiveUpdatesAndSendsCurrentSnapshotToNewSessions() throws Exception {
        TrafficUpdateWebSocketHandler handler = new TrafficUpdateWebSocketHandler(
                new TrafficUpdateStore(),
                new ObjectMapper().findAndRegisterModules());
        List<String> firstSessionMessages = new ArrayList<>();
        WebSocketSession firstSession = session("first", firstSessionMessages);
        handler.afterConnectionEstablished(firstSession);

        TrafficUpdate update = new TrafficUpdate(
                new ZoneId("zone-1"),
                Instant.parse("2026-11-14T20:00:00Z"),
                new TrafficMetrics(17, 8.5),
                TrafficStatus.MODERADA);
        handler.accept(update);

        List<String> secondSessionMessages = new ArrayList<>();
        handler.afterConnectionEstablished(session("second", secondSessionMessages));

        assertEquals(1, firstSessionMessages.size());
        assertEquals(1, secondSessionMessages.size());
        assertTrue(firstSessionMessages.get(0).contains("\"vehicleCount\":17"));
        assertTrue(secondSessionMessages.get(0).contains("\"status\":\"MODERADA\""));
    }

    private static WebSocketSession session(String id, List<String> sentMessages) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            sentMessages.add(message.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }
}
