package com.hololive.cardgame.dto;

import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.model.LobbyPlayer;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LobbyMatchResponse {
    private Long matchId;
    private String roomCode;
    private String status;
    private Long currentTurnPlayerId;
    private Integer turnNumber;
    private List<LobbyPlayerResponse> players;

    public static LobbyMatchResponse from(LobbyMatch match) {
        List<LobbyPlayerResponse> playerResponses = match.getPlayers()
            .stream()
            .map(LobbyPlayerResponse::from)
            .collect(Collectors.toList());

        return new LobbyMatchResponse(
            match.getId(),
            match.getRoomCode(),
            match.getStatus().name(),
            match.getCurrentTurnPlayerId(),
            match.getTurnNumber(),
            playerResponses
        );
    }
}
