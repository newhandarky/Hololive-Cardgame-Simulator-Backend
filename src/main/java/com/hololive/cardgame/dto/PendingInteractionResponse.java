package com.hololive.cardgame.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class PendingInteractionResponse {
    private Long interactionId;
    private String interactionType;
    private String sourceActionType;
    private Long sourceCardInstanceId;
    private String sourceCardId;
    private String effectType;
    private Integer minSelect;
    private Integer maxSelect;
    private Long targetHolomemCardInstanceId;
    private String title;
    private String message;
    private LocalDateTime createdAt;
    private final List<PendingDecisionCandidateResponse> cards = new ArrayList<>();
}
