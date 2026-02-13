package com.hololive.cardgame.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class LobbyMatch {
    private Long id;
    private String roomCode;
    private LobbyMatchStatus status = LobbyMatchStatus.WAITING;
    private Long currentTurnPlayerId;
    private Integer turnNumber = 1;
    private final List<LobbyPlayer> players = new ArrayList<>();
}
