package com.hololive.cardgame.dto;

import java.util.List;
import lombok.Data;

@Data
public class PlaySupportActionRequest {
    private Long cardInstanceId;
    private Long targetHolomemCardInstanceId;
    private List<Long> selectedCardInstanceIds;
}
