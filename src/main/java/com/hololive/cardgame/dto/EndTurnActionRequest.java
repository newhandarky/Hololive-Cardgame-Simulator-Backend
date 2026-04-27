package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class EndTurnActionRequest {
    private Integer requestedTurnNumber;
    private String idempotencyKey;
}
