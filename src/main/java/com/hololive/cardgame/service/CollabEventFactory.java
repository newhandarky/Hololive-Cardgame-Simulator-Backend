package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CollabEventFactory {

    public List<CollabEvent> createEvents(
        CollabAction action,
        CollabResolutionResult resolutionResult,
        CollabEffectResolution effectResolution
    ) {
        if (action == null || resolutionResult == null || effectResolution == null) {
            throw new IllegalArgumentException("COLLAB event 建立缺少必要上下文");
        }

        LocalDateTime occurredAt = LocalDateTime.now();
        List<CollabEvent> events = new ArrayList<>();
        int sequence = 1;

        events.add(createEvent(
            action,
            sequence++,
            CollabEventType.COLLAB_REQUEST_ACCEPTED,
            "PLAYER",
            resolutionResult.turnNumber(),
            Map.of(
                "sourceCardInstanceId", action.sourceCardInstanceId(),
                "targetZone", action.targetZone()
            ),
            occurredAt
        ));

        Map<String, Object> resolvedPayload = new LinkedHashMap<>();
        resolvedPayload.put("sourceHolomemId", resolutionResult.sourceHolomemId());
        resolvedPayload.put("sourceCardInstanceId", resolutionResult.sourceCardInstanceId());
        resolvedPayload.put("sourceCardId", resolutionResult.sourceCardId());
        resolvedPayload.put("sourceZone", resolutionResult.sourceZone());
        resolvedPayload.put("targetZone", resolutionResult.targetZone());
        resolvedPayload.put("holopowerCardInstanceId", resolutionResult.holopowerCardInstanceId());
        events.add(createEvent(
            action,
            sequence++,
            CollabEventType.COLLAB_RESOLVED,
            "BOARD",
            resolutionResult.turnNumber(),
            resolvedPayload,
            occurredAt
        ));

        if (effectResolution.hasDeferredCollabEffect()) {
            events.add(createEvent(
                action,
                sequence++,
                CollabEventType.COLLAB_EFFECT_PREVIEW_CREATED,
                "INTERACTION",
                resolutionResult.turnNumber(),
                Map.of(
                    "sourceCardInstanceId", resolutionResult.sourceCardInstanceId(),
                    "sourceCardId", resolutionResult.sourceCardId(),
                    "collabEffect", effectResolution.collabEffectSummary()
                ),
                occurredAt
            ));
        }

        if (!effectResolution.giftEffectSummary().isEmpty()) {
            events.add(createEvent(
                action,
                sequence++,
                CollabEventType.COLLAB_GIFT_PREVIEW_CREATED,
                "INTERACTION",
                resolutionResult.turnNumber(),
                Map.of(
                    "sourceCardInstanceId", resolutionResult.sourceCardInstanceId(),
                    "collabGiftEffect", effectResolution.giftEffectSummary(),
                    "triggerCount", effectResolution.giftTriggeredEffects().size()
                ),
                occurredAt
            ));
        }

        if (effectResolution.hasPendingInteraction()) {
            events.add(createEvent(
                action,
                sequence,
                CollabEventType.COLLAB_TRIGGER_CONFIRM_REQUIRED,
                "INTERACTION",
                resolutionResult.turnNumber(),
                Map.of(
                    "interactionId", effectResolution.pendingInteractionDecisionId(),
                    "interactionType", effectResolution.pendingInteractionDecisionType(),
                    "decisionType", "COLLAB_TRIGGER",
                    "sourceActionType", "COLLAB"
                ),
                occurredAt
            ));
        }

        return List.copyOf(events);
    }

    private CollabEvent createEvent(
        CollabAction action,
        int sequence,
        CollabEventType eventType,
        String targetScope,
        int turnNumber,
        Map<String, Object> payload,
        LocalDateTime occurredAt
    ) {
        return new CollabEvent(
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
