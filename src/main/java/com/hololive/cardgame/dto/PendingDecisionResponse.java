package com.hololive.cardgame.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class PendingDecisionResponse {
    private Long decisionId;
    private String decisionType;
    private String sourceActionType;
    private Long sourceCardInstanceId;
    private String sourceCardId;
    private String effectType;
    private Integer minSelect;
    private Integer maxSelect;
    private Long targetHolomemCardInstanceId;
    private LocalDateTime createdAt;
    private final List<PendingDecisionCandidateResponse> candidates = new ArrayList<>();
}
