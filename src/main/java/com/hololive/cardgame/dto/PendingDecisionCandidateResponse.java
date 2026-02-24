package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class PendingDecisionCandidateResponse {
    private Long cardInstanceId;
    private String cardId;
    private String name;
    private String cardType;
    private String levelType;
    private String zone;
}
