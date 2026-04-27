package com.hololive.cardgame.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PlayCardEventFactory {

    public List<PlayCardEvent> createEvents(
        PlayCardAction action,
        PlayCardResolutionResult resolutionResult,
        PlayCardEffectResolution effectResolution
    ) {
        if (action == null || resolutionResult == null || effectResolution == null) {
            throw new IllegalArgumentException("PLAY_CARD event 建立缺少必要上下文");
        }

        LocalDateTime occurredAt = LocalDateTime.now();
        List<PlayCardEvent> events = new ArrayList<>();
        int sequence = 1;

        events.add(createEvent(
            action,
            sequence++,
            PlayCardEventType.PLAY_CARD_REQUEST_ACCEPTED,
            "PLAYER",
            resolutionResult.turnNumber(),
            Map.of(
                "cardInstanceId", action.cardInstanceId(),
                "targetZone", action.targetZone(),
                "openingReset", action.openingReset()
            ),
            occurredAt
        ));

        Map<String, Object> resolvedPayload = new LinkedHashMap<>();
        resolvedPayload.put("cardInstanceId", resolutionResult.cardInstanceId());
        resolvedPayload.put("cardId", resolutionResult.cardId());
        resolvedPayload.put("targetZone", resolutionResult.targetZone());
        resolvedPayload.put("matchHolomemId", resolutionResult.matchHolomemId());
        resolvedPayload.put("enteredTurnNumber", resolutionResult.enteredTurnNumber());
        resolvedPayload.put("faceDown", resolutionResult.faceDown());
        resolvedPayload.put("currentLevel", resolutionResult.currentLevel());
        resolvedPayload.put("deferredUntilLiveStart", effectResolution.deferredUntilLiveStart());
        events.add(createEvent(
            action,
            sequence++,
            PlayCardEventType.PLAY_CARD_RESOLVED,
            "BOARD",
            resolutionResult.turnNumber(),
            resolvedPayload,
            occurredAt
        ));

        if (!effectResolution.deferredUntilLiveStart() && !effectResolution.triggerSummary().isEmpty()) {
            events.add(createEvent(
                action,
                sequence++,
                PlayCardEventType.PLAY_CARD_ENTER_HOOK_RESOLVED,
                "TRIGGER",
                resolutionResult.turnNumber(),
                Map.of("triggerSummary", effectResolution.triggerSummary()),
                occurredAt
            ));
        }

        if (!effectResolution.giftTriggeredEffects().isEmpty()) {
            events.add(createEvent(
                action,
                sequence++,
                PlayCardEventType.PLAY_CARD_GIFT_PREVIEW_CREATED,
                "INTERACTION",
                resolutionResult.turnNumber(),
                Map.of(
                    "giftEffectSummary", effectResolution.giftEffectSummary(),
                    "giftTriggerCount", effectResolution.giftTriggeredEffects().size()
                ),
                occurredAt
            ));
        }

        if (effectResolution.hasPendingInteraction()) {
            events.add(createEvent(
                action,
                sequence,
                PlayCardEventType.PLAY_CARD_GIFT_CONFIRM_REQUIRED,
                "INTERACTION",
                resolutionResult.turnNumber(),
                Map.of(
                    "decisionId", effectResolution.pendingInteractionDecisionId(),
                    "decisionType", effectResolution.pendingInteractionDecisionType(),
                    "triggerType", "STAGE_ENTER"
                ),
                occurredAt
            ));
        }

        return List.copyOf(events);
    }

    private PlayCardEvent createEvent(
        PlayCardAction action,
        int sequence,
        PlayCardEventType eventType,
        String targetScope,
        int turnNumber,
        Map<String, Object> payload,
        LocalDateTime occurredAt
    ) {
        return new PlayCardEvent(
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
