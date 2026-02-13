package com.hololive.cardgame.config;

import com.hololive.cardgame.websocket.MatchSocketHandler;
import com.hololive.cardgame.websocket.WsAuthHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MatchSocketHandler matchSocketHandler;
    private final WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor;

    public WebSocketConfig(
        MatchSocketHandler matchSocketHandler,
        WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor
    ) {
        this.matchSocketHandler = matchSocketHandler;
        this.wsAuthHandshakeInterceptor = wsAuthHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(matchSocketHandler, "/ws/matches/{matchId}")
            .addInterceptors(wsAuthHandshakeInterceptor)
            .setAllowedOrigins("http://localhost:5173", "http://localhost:5174");
    }
}
