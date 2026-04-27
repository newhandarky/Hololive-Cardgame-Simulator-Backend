package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import java.util.Map;

public record EndTurnContext(
    MatchEntity match,
    Long actorUserId,
    Long opponentUserId,
    MatchPhase currentPhase,
    int currentTurnNumber,
    EndTurnRequiredActionSummary requiredTurnActionSummary,
    int clearedEffectCount,
    int resetRestedCount,
    Map<String, Object> centerReplenishSummary
) {
}
