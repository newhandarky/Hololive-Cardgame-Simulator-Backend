package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.UUID;

public record CollabAction(
    String actionType,
    Long matchId,
    Long actorUserId,
    Long sourceCardInstanceId,
    String targetZone,
    int requestedTurnNumber,
    ActionSource source,
    String traceId,
    String idempotencyKey,
    LocalDateTime requestedAt
) {

    private static final String ACTION_TYPE = "COLLAB";
    private static final String TARGET_ZONE = "COLLAB";

    public static CollabAction fromApi(
        Long matchId,
        Long actorUserId,
        Long sourceCardInstanceId,
        int requestedTurnNumber,
        String idempotencyKey
    ) {
        String traceId = UUID.randomUUID().toString();
        return new CollabAction(
            ACTION_TYPE,
            matchId,
            actorUserId,
            sourceCardInstanceId,
            TARGET_ZONE,
            requestedTurnNumber,
            ActionSource.API,
            traceId,
            idempotencyKey == null || idempotencyKey.isBlank()
                ? "legacy-collab:%s:%s:%s:%s".formatted(
                    matchId,
                    actorUserId,
                    sourceCardInstanceId,
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
