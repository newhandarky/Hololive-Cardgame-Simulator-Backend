package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AttachCheerEventFactory {

    public List<AttachCheerEvent> createEvents(
        AttachCheerAction action,
        AttachCheerResolutionResult resolutionResult
    ) {
        if (action == null || resolutionResult == null) {
            throw new IllegalArgumentException("ATTACH_CHEER event 建立缺少必要上下文");
        }

        LocalDateTime occurredAt = LocalDateTime.now();
        List<AttachCheerEvent> events = new ArrayList<>();
        int sequence = 1;

        events.add(createEvent(
            action,
            sequence++,
            AttachCheerEventType.ATTACH_CHEER_REQUEST_ACCEPTED,
            "PLAYER",
            resolutionResult.turnNumber(),
            Map.of(
                "cheerCardInstanceId", action.cheerCardInstanceId(),
                "targetHolomemCardInstanceId", action.targetHolomemCardInstanceId()
            ),
            occurredAt
        ));

        Map<String, Object> resolvedPayload = new LinkedHashMap<>();
        resolvedPayload.put("cheerCardInstanceId", resolutionResult.cheerCardInstanceId());
        resolvedPayload.put("cheerCardId", resolutionResult.cheerCardId());
        resolvedPayload.put("sourceZone", resolutionResult.sourceZone());
        resolvedPayload.put("targetHolomemId", resolutionResult.targetHolomemId());
        resolvedPayload.put("targetHolomemCardInstanceId", resolutionResult.targetHolomemCardInstanceId());
        resolvedPayload.put("attachmentId", resolutionResult.attachmentId());
        events.add(createEvent(
            action,
            sequence,
            AttachCheerEventType.ATTACH_CHEER_RESOLVED,
            "BOARD",
            resolutionResult.turnNumber(),
            resolvedPayload,
            occurredAt
        ));

        return List.copyOf(events);
    }

    private AttachCheerEvent createEvent(
        AttachCheerAction action,
        int sequence,
        AttachCheerEventType eventType,
        String targetScope,
        int turnNumber,
        Map<String, Object> payload,
        LocalDateTime occurredAt
    ) {
        return new AttachCheerEvent(
            action.traceId() + ":" + eventType.name() + ":" + sequence,
            eventType,
            targetScope,
            action.matchId(),
            action.actorUserId(),
            turnNumber,
            action.actionType(),
            action.traceId(),
            occurredAt,
            payload
        );
    }
}
