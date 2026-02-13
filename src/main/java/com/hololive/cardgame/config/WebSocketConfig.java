package com.hololive.cardgame.config;

import com.hololive.cardgame.websocket.MatchSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MatchSocketHandler matchSocketHandler;

    public WebSocketConfig(MatchSocketHandler matchSocketHandler) {
        this.matchSocketHandler = matchSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(matchSocketHandler, "/ws/matches/{matchId}")
            .setAllowedOrigins("http://localhost:5173", "http://localhost:5174");
    }
}

