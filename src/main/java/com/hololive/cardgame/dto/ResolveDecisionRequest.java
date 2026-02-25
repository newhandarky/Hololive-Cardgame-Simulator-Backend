package com.hololive.cardgame.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ResolveDecisionRequest {
    private Long decisionId;
    private List<Long> selectedCardInstanceIds = new ArrayList<>();
    private String placement;
}
