package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.Map;

public record CollabEvent(
    String eventId,
    CollabEventType eventType,
    String targetScope,
    Long matchId,
    Long actorUserId,
    int turnNumber,
    String sourceActionType,
    String sourceTraceId,
    LocalDateTime occurredAt,
    Map<String, Object> payload
) {
}
