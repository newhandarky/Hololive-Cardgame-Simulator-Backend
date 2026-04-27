package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;

public record AttachCheerResolutionResult(
    MatchEntity match,
    Long actingUserId,
    int turnNumber,
    Long cheerCardInstanceId,
    String cheerCardId,
    String sourceZone,
    Long targetHolomemId,
    Long targetHolomemCardInstanceId,
    Long attachmentId
) {
}
