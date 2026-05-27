package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

record PendingDecision(
    Long decisionId,
    String decisionType,
    String sourceActionType,
    Long sourceCardInstanceId,
    String sourceCardId,
    String effectType,
    int minSelect,
    int maxSelect,
    Long targetHolomemCardInstanceId,
    String targetType,
    String effectJson,
    List<Long> candidateCardInstanceIds,
    boolean limited,
    JsonNode contextNode
) {
}
