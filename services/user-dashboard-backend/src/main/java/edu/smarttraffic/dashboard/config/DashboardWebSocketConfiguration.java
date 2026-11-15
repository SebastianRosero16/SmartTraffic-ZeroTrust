package edu.smarttraffic.dashboard.config;

import edu.smarttraffic.dashboard.adapter.in.websocket.TrafficUpdateWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class DashboardWebSocketConfiguration implements WebSocketConfigurer {
    private static final String TRAFFIC_UPDATES_PATH = "/ws/traffic";

    private final TrafficUpdateWebSocketHandler handler;

    public DashboardWebSocketConfiguration(TrafficUpdateWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, TRAFFIC_UPDATES_PATH);
    }
}
