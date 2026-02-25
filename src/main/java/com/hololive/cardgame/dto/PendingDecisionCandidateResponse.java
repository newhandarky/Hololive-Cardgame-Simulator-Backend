package com.hololive.cardgame.dto;

import java.util.Map;
import lombok.Data;

@Data
public class PendingDecisionCandidateResponse {
    private Long cardInstanceId;
    private String cardId;
    private String name;
    private String cardType;
    private String levelType;
    private String zone;
    private String imageUrl;
    private Integer currentHp;
    private Integer maxHp;
    private Integer damageTaken;
    private Integer cheerCount;
    private Map<String, Integer> cheerColorCounts;
    private Integer attachedSupportCount;
}
