package com.hololive.cardgame.dto;

import com.hololive.cardgame.model.LobbyPlayer;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LobbyPlayerResponse {
    private Long userId;
    private boolean ready;

    public static LobbyPlayerResponse from(LobbyPlayer player) {
        return new LobbyPlayerResponse(player.getUserId(), player.isReady());
    }
}

