package com.hololive.cardgame.dto;

import java.util.ArrayList;
import java.util.List;
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
    private final List<BoardZoneStateResponse> boardZones = new ArrayList<>();
    private final List<ZoneCardInstanceResponse> handCards = new ArrayList<>();

    public PlayerZoneStateResponse(Long userId) {
        this.userId = userId;
        initializeBoardZones();
    }

    private void initializeBoardZones() {
        boardZones.add(new BoardZoneStateResponse(1, "OSHI"));
        boardZones.add(new BoardZoneStateResponse(2, "CENTER"));
        boardZones.add(new BoardZoneStateResponse(3, "COLLAB"));
        boardZones.add(new BoardZoneStateResponse(4, "BACK"));
        boardZones.add(new BoardZoneStateResponse(5, "DECK"));
        boardZones.add(new BoardZoneStateResponse(6, "ARCHIVE"));
        boardZones.add(new BoardZoneStateResponse(7, "HOLOPOWER"));
        boardZones.add(new BoardZoneStateResponse(8, "CHEER_DECK"));
        boardZones.add(new BoardZoneStateResponse(9, "LIFE"));
    }
}
