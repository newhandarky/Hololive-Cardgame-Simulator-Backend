package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class PlayerZoneStateResponse {
    private Long userId;
    private Integer oshiCount = 0;
    private Integer centerCount = 0;
    private Integer collabCount = 0;
    private Integer backCount = 0;
    private Integer deckCount = 0;
    private Integer archiveCount = 0;
    private Integer holopowerCount = 0;
    private Integer cheerDeckCount = 0;
    private Integer lifeCount = 0;
    private Integer handCount = 0;

    public PlayerZoneStateResponse(Long userId) {
        this.userId = userId;
    }
}
