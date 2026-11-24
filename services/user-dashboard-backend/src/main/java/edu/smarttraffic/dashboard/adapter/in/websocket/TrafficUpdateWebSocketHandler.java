package edu.smarttraffic.dashboard.adapter.in.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.dashboard.application.TrafficUpdateStore;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public final class TrafficUpdateWebSocketHandler extends TextWebSocketHandler {
    private static final System.Logger LOGGER =
            System.getLogger(TrafficUpdateWebSocketHandler.class.getName());
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final TrafficUpdateStore updateStore;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public TrafficUpdateWebSocketHandler(TrafficUpdateStore updateStore, ObjectMapper objectMapper) {
        this.updateStore = updateStore;
        this.objectMapper = objectMapper;
    }

    public void accept(TrafficUpdate update) {
        if (!updateStore.accept(update)) {
            return;
        }
        broadcast(update);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session,
                SEND_TIME_LIMIT_MILLIS,
                BUFFER_SIZE_LIMIT_BYTES);
        sessions.put(session.getId(), safeSession);
        updateStore.snapshot().forEach(update -> send(safeSession, update));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Dashboard feed is read-only"));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOGGER.log(System.Logger.Level.WARNING,
                "Dashboard WebSocket transport failed for session " + session.getId(),
                exception);
        closeSession(session.getId());
    }

    private void broadcast(TrafficUpdate update) {
        for (WebSocketSession session : sessions.values()) {
            send(session, update);
        }
    }

    private void send(WebSocketSession session, TrafficUpdate update) {
        if (!session.isOpen()) {
            closeSession(session.getId());
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(update)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize traffic update for dashboard", exception);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not send traffic update to dashboard session " + session.getId(),
                    exception);
            closeSession(session.getId());
        }
    }

    private void closeSession(String sessionId) {
        WebSocketSession session = sessions.remove(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not close dashboard WebSocket session " + sessionId,
                    exception);
        }
    }
}
