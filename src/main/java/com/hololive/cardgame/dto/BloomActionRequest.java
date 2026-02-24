package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class BloomActionRequest {
    private Long bloomCardInstanceId;
    private Long targetHolomemCardInstanceId;
}
