package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BloomEventFactory {

    public List<BloomEvent> createEvents(BloomAction action, BloomResolutionResult resolutionResult) {
        LocalDateTime occurredAt = LocalDateTime.now();
        List<BloomEvent> events = new ArrayList<>();
        int sequence = 1;

        events.add(createEvent(
            action,
            sequence++,
            BloomEventType.BLOOM_REQUEST_ACCEPTED,
            "PLAYER",
            resolutionResult.turnNumber(),
            Map.of(
                "sourceCardInstanceId", action.sourceCardInstanceId(),
                "targetHolomemCardInstanceId", action.targetHolomemCardInstanceId()
            ),
            occurredAt
        ));

        Map<String, Object> resolvedPayload = new LinkedHashMap<>();
        resolvedPayload.put("targetHolomemId", resolutionResult.targetHolomemId());
        resolvedPayload.put("targetHolomemCardInstanceId", resolutionResult.targetHolomemCardInstanceId());
        resolvedPayload.put("fromCardId", resolutionResult.targetPreviousCardId());
        resolvedPayload.put("fromLevel", resolutionResult.targetPreviousLevelType());
        resolvedPayload.put("toCardInstanceId", resolutionResult.sourceCardInstanceId());
        resolvedPayload.put("toCardId", resolutionResult.sourceCardId());
        resolvedPayload.put("toLevel", resolutionResult.sourceLevelType());
        resolvedPayload.put("damageCarried", resolutionResult.damageCarried());
        resolvedPayload.put("stackDepth", resolutionResult.stackDepth());
        resolvedPayload.put("consumedExtraBloomAllowanceId", resolutionResult.consumedExtraBloomAllowanceId());
        events.add(createEvent(
            action,
            sequence++,
            BloomEventType.BLOOM_RESOLVED,
            "BOARD",
            resolutionResult.turnNumber(),
            resolvedPayload,
            occurredAt
        ));

        if (!resolutionResult.bloomEffectSummary().isEmpty()) {
            events.add(createEvent(
                action,
                sequence++,
                BloomEventType.BLOOM_EFFECT_PREVIEW_CREATED,
                "INTERACTION",
                resolutionResult.turnNumber(),
                Map.of(
                    "sourceCardInstanceId", resolutionResult.sourceCardInstanceId(),
                    "sourceCardId", resolutionResult.sourceCardId(),
                    "bloomEffect", resolutionResult.bloomEffectSummary()
                ),
                occurredAt
            ));
        }

        if (resolutionResult.followupInteractionId() != null) {
            events.add(createEvent(
                action,
                sequence,
                BloomEventType.BLOOM_TRIGGER_CONFIRM_REQUIRED,
                "INTERACTION",
                resolutionResult.turnNumber(),
                Map.of(
                    "interactionId", resolutionResult.followupInteractionId(),
                    "interactionType", "TRIGGERED_EFFECT_CONFIRM",
                    "decisionType", "BLOOM_EFFECT",
                    "sourceActionType", "BLOOM"
                ),
                occurredAt
            ));
        }

        return List.copyOf(events);
    }

    private BloomEvent createEvent(
        BloomAction action,
        int sequence,
        BloomEventType eventType,
        String targetScope,
        int turnNumber,
        Map<String, Object> payload,
        LocalDateTime occurredAt
    ) {
        return new BloomEvent(
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
