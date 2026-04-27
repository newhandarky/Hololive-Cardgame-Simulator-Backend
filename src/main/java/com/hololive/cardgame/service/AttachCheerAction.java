package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.UUID;

public record AttachCheerAction(
    String actionType,
    Long matchId,
    Long actorUserId,
    Long cheerCardInstanceId,
    Long targetHolomemCardInstanceId,
    int requestedTurnNumber,
    ActionSource source,
    String traceId,
    String idempotencyKey,
    LocalDateTime requestedAt
) {

    private static final String ACTION_TYPE = "ATTACH_CHEER";

    public static AttachCheerAction fromApi(
        Long matchId,
        Long actorUserId,
        Long cheerCardInstanceId,
        Long targetHolomemCardInstanceId,
        int requestedTurnNumber,
        String idempotencyKey
    ) {
        String traceId = UUID.randomUUID().toString();
        return new AttachCheerAction(
            ACTION_TYPE,
            matchId,
            actorUserId,
            cheerCardInstanceId,
            targetHolomemCardInstanceId,
            requestedTurnNumber,
            ActionSource.API,
            traceId,
            idempotencyKey == null || idempotencyKey.isBlank()
                ? "legacy-attach-cheer:%s:%s:%s:%s:%s".formatted(
                    matchId,
                    actorUserId,
                    cheerCardInstanceId,
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
