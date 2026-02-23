package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class AttackArtActionRequest {
    private Long attackerCardInstanceId;
    private Long targetCardInstanceId;
}
