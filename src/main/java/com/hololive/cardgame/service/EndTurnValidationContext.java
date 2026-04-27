package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;

public record EndTurnValidationContext(
    MatchEntity match,
    Long actorUserId,
    Long opponentUserId,
    int currentTurnNumber,
    Long currentTurnPlayerId,
    MatchPhase currentPhase,
    String matchStatus,
    String lobbyStatus,
    boolean duplicateAction,
    boolean actorPendingInteractions,
    boolean anyPendingInteractions,
    EndTurnRequiredActionSummary requiredTurnActionSummary
) {
}
