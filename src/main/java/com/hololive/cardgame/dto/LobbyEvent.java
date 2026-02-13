package com.hololive.cardgame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LobbyEvent {
    private String type;
    private LobbyMatchResponse match;

    public static LobbyEvent of(String type, LobbyMatchResponse match) {
        return new LobbyEvent(type, match);
    }
}

