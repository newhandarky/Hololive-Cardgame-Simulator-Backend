package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class PlayToStageActionRequest {
    private Long cardInstanceId;
    private String targetZone;
}
