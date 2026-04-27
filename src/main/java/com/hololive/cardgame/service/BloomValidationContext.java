package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;

public record BloomValidationContext(
    MatchEntity match,
    Long actorUserId,
    int currentTurnNumber,
    Long currentTurnPlayerId,
    MatchPhase currentPhase,
    String matchStatus,
    String lobbyStatus,
    boolean duplicateAction,
    boolean actorPendingInteractions,
    BloomSourceCardSnapshot sourceCard,
    BloomTargetSnapshot target
) {
}
