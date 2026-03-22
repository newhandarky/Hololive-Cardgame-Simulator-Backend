package com.hololive.cardgame.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class BatonTouchActionRequest {
    private Long sourceHolomemCardInstanceId;

    @JsonAlias("targetBackHolomemCardInstanceId")
    private Long targetCenterHolomemCardInstanceId;
}
