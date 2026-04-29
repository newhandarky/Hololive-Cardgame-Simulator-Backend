package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

record FollowupInteractionContext(
    String decisionType,
    String title,
    String message,
    int minSelect,
    int maxSelect,
    List<Map<String, Object>> cards,
    List<Long> candidateCardInstanceIds,
    List<String> placementOptions,
    Long lookedCardInstanceId,
    String lookedCardId
) {
}
