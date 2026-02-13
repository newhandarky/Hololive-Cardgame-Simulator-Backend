package com.hololive.cardgame.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.LobbyEvent;
import com.hololive.cardgame.service.LobbyMatchService;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class MatchSocketHandler extends TextWebSocketHandler {

    private final Map<Long, Set<WebSocketSession>> sessionsByMatchId = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LobbyMatchService lobbyMatchService;

    public MatchSocketHandler(LobbyMatchService lobbyMatchService) {
        this.lobbyMatchService = lobbyMatchService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long matchId = parseMatchId(session.getUri());
        Long userId = parseUserId(session);
        if (matchId == null || userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        if (!lobbyMatchService.isUserInMatch(matchId, userId)) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        sessionsByMatchId
            .computeIfAbsent(matchId, key -> ConcurrentHashMap.newKeySet())
            .add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionsByMatchId.values().forEach(sessions -> sessions.remove(session));
    }

    public void publish(Long matchId, LobbyEvent event) {
        Set<WebSocketSession> sessions = sessionsByMatchId.get(matchId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return;
        }

        TextMessage message = new TextMessage(payload);
        sessions.removeIf(session -> !session.isOpen());
        for (WebSocketSession session : sessions) {
            try {
                session.sendMessage(message);
            } catch (IOException ignored) {
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException closeIgnored) {
                    // Ignore close failure.
                }
            }
        }
    }

    private Long parseMatchId(URI uri) {
        if (uri == null) {
            return null;
        }

        String path = uri.getPath();
        if (path == null || !path.startsWith("/ws/matches/")) {
            return null;
        }

        String matchIdText = path.substring("/ws/matches/".length());
        try {
            return Long.parseLong(matchIdText);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get(WsAuthHandshakeInterceptor.USER_ID_ATTR);
        if (userId instanceof Long value) {
            return value;
        }
        if (userId instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
