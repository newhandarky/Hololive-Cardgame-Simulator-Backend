package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.UUID;

public record PlayCardAction(
    String actionType,
    Long matchId,
    Long actorUserId,
    Long cardInstanceId,
    String targetZone,
    int requestedTurnNumber,
    boolean openingReset,
    ActionSource source,
    String traceId,
    String idempotencyKey,
    LocalDateTime requestedAt
) {

    private static final String ACTION_TYPE = "PLAY_CARD";

    public static PlayCardAction fromApi(
        Long matchId,
        Long actorUserId,
        Long cardInstanceId,
        String targetZone,
        int requestedTurnNumber,
        boolean openingReset,
        String idempotencyKey
    ) {
        String normalizedTargetZone = normalize(targetZone);
        String traceId = UUID.randomUUID().toString();
        return new PlayCardAction(
            ACTION_TYPE,
            matchId,
            actorUserId,
            cardInstanceId,
            normalizedTargetZone,
            requestedTurnNumber,
            openingReset,
            ActionSource.API,
            traceId,
            idempotencyKey == null || idempotencyKey.isBlank()
                ? "legacy-play-card:%s:%s:%s:%s:%s".formatted(
                    matchId,
                    actorUserId,
                    cardInstanceId,
                    normalizedTargetZone,
                    requestedTurnNumber
                )
                : idempotencyKey,
            LocalDateTime.now()
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    public enum ActionSource {
        API,
        WS,
        SYSTEM,
        TEST
    }
}
