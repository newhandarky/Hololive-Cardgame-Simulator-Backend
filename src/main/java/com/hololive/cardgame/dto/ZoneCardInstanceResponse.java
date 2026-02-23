package com.hololive.cardgame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ZoneCardInstanceResponse {
    private Long cardInstanceId;
    private String cardId;
    private String zone;
    private Integer positionIndex;
    private Long ownerUserId;
    private Boolean faceDown;
}
