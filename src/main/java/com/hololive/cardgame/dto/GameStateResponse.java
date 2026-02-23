package com.hololive.cardgame.dto;

import com.hololive.cardgame.model.MatchPhase;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class GameStateResponse {
    private Long matchId;
    private String roomCode;
    private String status;
    private MatchPhase phase = MatchPhase.RESET;
    private Long currentTurnPlayerId;
    private Integer turnNumber;
    private final List<PlayerZoneStateResponse> players = new ArrayList<>();
}
