package com.hololive.cardgame.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class BoardZoneStateResponse {
    private Integer slotIndex;
    private String zone;
    private final List<ZoneCardInstanceResponse> cards = new ArrayList<>();

    public BoardZoneStateResponse(Integer slotIndex, String zone) {
        this.slotIndex = slotIndex;
        this.zone = zone;
    }
}
