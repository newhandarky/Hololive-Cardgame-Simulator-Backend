package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.Map;

public record AttachCheerEvent(
    String eventId,
    AttachCheerEventType eventType,
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
