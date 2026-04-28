package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;

public record AttackFinishCheckContext(
    MatchEntity match,
    Long actorUserId,
    int turnNumber,
    Object effectSummaryForChecks
) {
    public static AttackFinishCheckContext attackArt(
        MatchEntity match,
        Long actorUserId,
        int turnNumber,
        Object effectSummaryForChecks
    ) {
        return new AttackFinishCheckContext(match, actorUserId, turnNumber, effectSummaryForChecks);
    }
}
