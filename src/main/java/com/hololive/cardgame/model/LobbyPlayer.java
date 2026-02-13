package com.hololive.cardgame.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LobbyPlayer {
    private Long userId;
    private boolean ready;
}

