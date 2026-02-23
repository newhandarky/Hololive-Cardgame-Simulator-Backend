package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.AttachCheerActionRequest;
import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.JoinMatchRequest;
import com.hololive.cardgame.dto.LobbyEvent;
import com.hololive.cardgame.dto.LobbyMatchResponse;
import com.hololive.cardgame.dto.ReadyRequest;
import com.hololive.cardgame.dto.GameStateResponse;
import com.hololive.cardgame.dto.PlaySupportActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.service.AuthUserResolver;
import com.hololive.cardgame.service.LobbyMatchService;
import com.hololive.cardgame.service.MatchActionService;
import com.hololive.cardgame.service.MatchGameStateService;
import com.hololive.cardgame.websocket.MatchSocketHandler;
import org.springframework.http.HttpStatus;
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
    private final MatchActionService matchActionService;
    private final MatchGameStateService matchGameStateService;
    private final MatchSocketHandler matchSocketHandler;
    private final AuthUserResolver authUserResolver;

    public MatchController(
        LobbyMatchService lobbyMatchService,
        MatchActionService matchActionService,
        MatchGameStateService matchGameStateService,
        MatchSocketHandler matchSocketHandler,
        AuthUserResolver authUserResolver
    ) {
        this.lobbyMatchService = lobbyMatchService;
        this.matchActionService = matchActionService;
        this.matchGameStateService = matchGameStateService;
        this.matchSocketHandler = matchSocketHandler;
        this.authUserResolver = authUserResolver;
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

    @PostMapping("/{matchId}/actions/play-to-stage")
    public LobbyMatchResponse playToStage(
        @PathVariable Long matchId,
        @RequestBody PlayToStageActionRequest request
    ) {
        try {
            matchActionService.playToStage(matchId, currentUserId(), request);
            LobbyMatchResponse response = LobbyMatchResponse.from(lobbyMatchService.getMatch(matchId));
            publish(matchId, "PLAY_TO_STAGE", response);
            return response;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{matchId}/actions/play-support")
    public LobbyMatchResponse playSupport(
        @PathVariable Long matchId,
        @RequestBody PlaySupportActionRequest request
    ) {
        try {
            matchActionService.playSupport(matchId, currentUserId(), request);
            LobbyMatchResponse response = LobbyMatchResponse.from(lobbyMatchService.getMatch(matchId));
            publish(matchId, "PLAY_SUPPORT", response);
            return response;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{matchId}/actions/attach-cheer")
    public LobbyMatchResponse attachCheer(
        @PathVariable Long matchId,
        @RequestBody AttachCheerActionRequest request
    ) {
        try {
            matchActionService.attachCheer(matchId, currentUserId(), request);
            LobbyMatchResponse response = LobbyMatchResponse.from(lobbyMatchService.getMatch(matchId));
            publish(matchId, "ATTACH_CHEER", response);
            return response;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{matchId}/actions/attack-art")
    public LobbyMatchResponse attackArt(
        @PathVariable Long matchId,
        @RequestBody AttackArtActionRequest request
    ) {
        try {
            matchActionService.attackArt(matchId, currentUserId(), request);
            LobbyMatchResponse response = LobbyMatchResponse.from(lobbyMatchService.getMatch(matchId));
            publish(matchId, "ATTACK_ART", response);
            return response;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{matchId}/actions/end-turn")
    public LobbyMatchResponse endTurn(@PathVariable Long matchId) {
        try {
            matchActionService.endTurn(matchId, currentUserId());
            LobbyMatchResponse response = LobbyMatchResponse.from(lobbyMatchService.getMatch(matchId));
            publish(matchId, "TURN_ENDED", response);
            return response;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/{matchId}/state")
    public GameStateResponse getMatchState(@PathVariable Long matchId) {
        try {
            return matchGameStateService.getGameStateForUser(matchId, currentUserId());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private void publish(Long matchId, String eventType, LobbyMatchResponse response) {
        GameStateResponse state = matchGameStateService.getGameState(matchId);
        matchSocketHandler.publish(matchId, LobbyEvent.of(eventType, response, state));
    }

    private Long currentUserId() {
        return authUserResolver.currentUserId();
    }
}
