package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;

public record CollabValidationContext(
    MatchEntity match,
    Long actorUserId,
    int currentTurnNumber,
    Long currentTurnPlayerId,
    MatchPhase currentPhase,
    String matchStatus,
    String lobbyStatus,
    boolean duplicateAction,
    boolean actorPendingInteractions,
    boolean stageActionLocked,
    boolean collabUsedThisTurn,
    int targetZoneOccupiedCount,
    CollabSourceHolomemSnapshot sourceHolomem
) {
}
