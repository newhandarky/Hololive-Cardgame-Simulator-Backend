package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class MoveStageHolomemActionRequest {
    private Long cardInstanceId;
    private String targetZone;
}
