package com.hololive.cardgame.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class GameStateResponse {
    private Long matchId;
    private String roomCode;
    private String status;
    private Long currentTurnPlayerId;
    private Integer turnNumber;
    private final List<PlayerZoneStateResponse> players = new ArrayList<>();
}
