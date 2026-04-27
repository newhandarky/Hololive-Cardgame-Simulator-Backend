package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;

public record CollabResolutionResult(
    MatchEntity match,
    Long actingUserId,
    int turnNumber,
    Long sourceHolomemId,
    Long sourceCardInstanceId,
    String sourceCardId,
    String sourceZone,
    String targetZone,
    Long holopowerCardInstanceId
) {
}
