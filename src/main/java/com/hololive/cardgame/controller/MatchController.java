package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.JoinMatchRequest;
import com.hololive.cardgame.dto.LobbyEvent;
import com.hololive.cardgame.dto.LobbyMatchResponse;
import com.hololive.cardgame.dto.ReadyRequest;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.service.LobbyMatchService;
import com.hololive.cardgame.websocket.MatchSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final LobbyMatchService lobbyMatchService;
    private final MatchSocketHandler matchSocketHandler;

    public MatchController(LobbyMatchService lobbyMatchService, MatchSocketHandler matchSocketHandler) {
        this.lobbyMatchService = lobbyMatchService;
        this.matchSocketHandler = matchSocketHandler;
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public LobbyMatchResponse createMatch() {
        Long userId = currentUserId();
        LobbyMatch match = lobbyMatchService.createMatch(userId);
        LobbyMatchResponse response = LobbyMatchResponse.from(match);
        publish(match.getId(), "MATCH_CREATED", response);
        return response;
    }

    @PostMapping("/join")
    public LobbyMatchResponse joinMatch(@RequestBody JoinMatchRequest request) {
        try {
            LobbyMatch match = lobbyMatchService.joinMatch(request.getRoomCode(), currentUserId());
            LobbyMatchResponse response = LobbyMatchResponse.from(match);
            publish(match.getId(), "USER_JOINED", response);
            return response;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/{matchId}")
    public LobbyMatchResponse getMatch(@PathVariable Long matchId) {
        try {
            return LobbyMatchResponse.from(lobbyMatchService.getMatch(matchId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{matchId}/ready")
    public LobbyMatchResponse setReady(@PathVariable Long matchId, @RequestBody ReadyRequest request) {
        try {
            LobbyMatch match = lobbyMatchService.setReady(matchId, currentUserId(), request.isReady());
            LobbyMatchResponse response = LobbyMatchResponse.from(match);
            publish(matchId, "READY_UPDATED", response);
            return response;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{matchId}/start")
    public LobbyMatchResponse startMatch(@PathVariable Long matchId) {
        try {
            LobbyMatch match = lobbyMatchService.startMatch(matchId, currentUserId());
            LobbyMatchResponse response = LobbyMatchResponse.from(match);
            publish(matchId, "MATCH_STARTED", response);
            return response;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private void publish(Long matchId, String eventType, LobbyMatchResponse response) {
        matchSocketHandler.publish(matchId, LobbyEvent.of(eventType, response));
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Long userId) {
            return userId;
        }
        if (principal instanceof String text && text.matches("\\d+")) {
            return Long.valueOf(text);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登入");
    }
}

