package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;

public record PlayCardResolutionResult(
    MatchEntity match,
    Long actorUserId,
    int turnNumber,
    Long cardInstanceId,
    String cardId,
    String sourceZone,
    String targetZone,
    Long matchHolomemId,
    int enteredTurnNumber,
    boolean faceDown,
    String currentLevel,
    boolean openingReset
) {
}
