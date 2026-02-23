package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class AttachCheerActionRequest {
    private Long cheerCardInstanceId;
    private Long targetHolomemCardInstanceId;
}
