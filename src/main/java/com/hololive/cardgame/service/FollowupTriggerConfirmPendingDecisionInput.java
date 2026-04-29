package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

record FollowupTriggerConfirmPendingDecisionInput(
    Long matchId,
    Long userId,
    String sourceActionType,
    Long sourceCardInstanceId,
    String sourceCardId,
    String effectType,
    String title,
    String message,
    List<Map<String, Object>> cards,
    int turnNumber,
    Map<String, Object> additionalContext
) {
}
