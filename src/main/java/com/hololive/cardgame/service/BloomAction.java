package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.UUID;

public record BloomAction(
    String actionType,
    Long matchId,
    Long actorUserId,
    Long sourceCardInstanceId,
    Long targetHolomemCardInstanceId,
    int requestedTurnNumber,
    ActionSource source,
    String traceId,
    String idempotencyKey,
    LocalDateTime requestedAt
) {

    private static final String ACTION_TYPE = "BLOOM";

    public static BloomAction fromApi(
        Long matchId,
        Long actorUserId,
        Long sourceCardInstanceId,
        Long targetHolomemCardInstanceId,
        int requestedTurnNumber,
        String idempotencyKey
    ) {
        String traceId = UUID.randomUUID().toString();
        return new BloomAction(
            ACTION_TYPE,
            matchId,
            actorUserId,
            sourceCardInstanceId,
            targetHolomemCardInstanceId,
            requestedTurnNumber,
            ActionSource.API,
            traceId,
            idempotencyKey == null || idempotencyKey.isBlank()
                ? "legacy-bloom:%s:%s:%s:%s:%s".formatted(
                    matchId,
                    actorUserId,
                    sourceCardInstanceId,
                    targetHolomemCardInstanceId,
                    requestedTurnNumber
                )
                : idempotencyKey,
            LocalDateTime.now()
        );
    }

    public enum ActionSource {
        API,
        WS,
        SYSTEM,
        TEST
    }
}
