package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class BatonTouchActionRequest {
    private Long sourceHolomemCardInstanceId;
    private Long targetBackHolomemCardInstanceId;
}
