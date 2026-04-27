package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import java.util.Map;

public record EndTurnResolutionResult(
    MatchEntity match,
    Long actingUserId,
    Long nextTurnPlayerId,
    int currentTurnNumber,
    int nextTurnNumber,
    MatchPhase nextPhase,
    Map<String, Object> endTurnActionPayload
) {
}
