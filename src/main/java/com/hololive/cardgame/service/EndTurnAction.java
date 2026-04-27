package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.UUID;

public record EndTurnAction(
    String actionType,
    Long matchId,
    Long actorUserId,
    int requestedTurnNumber,
    ActionSource source,
    String traceId,
    String idempotencyKey,
    LocalDateTime requestedAt
) {

    private static final String ACTION_TYPE = "END_TURN";

    public static EndTurnAction fromApi(
        Long matchId,
        Long actorUserId,
        int requestedTurnNumber,
        String idempotencyKey
    ) {
        String traceId = UUID.randomUUID().toString();
        return new EndTurnAction(
            ACTION_TYPE,
            matchId,
            actorUserId,
            requestedTurnNumber,
            ActionSource.API,
            traceId,
            idempotencyKey == null || idempotencyKey.isBlank()
                ? "legacy-end-turn:%s:%s:%s".formatted(matchId, actorUserId, requestedTurnNumber)
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
