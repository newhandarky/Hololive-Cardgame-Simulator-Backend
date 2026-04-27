package com.hololive.cardgame.service;

import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EndTurnEventFactory {

    public List<EndTurnEvent> createCoreEvents(
        EndTurnAction action,
        EndTurnContext context,
        EndTurnResolutionResult resolutionResult
    ) {
        LocalDateTime occurredAt = LocalDateTime.now();
        List<EndTurnEvent> events = new ArrayList<>();
        int sequence = 1;

        events.add(createEvent(
            action,
            sequence++,
            EndTurnEventType.TURN_ENDING,
            "MATCH",
            context.currentTurnNumber(),
            Map.of(
                "currentPhase", context.currentPhase().name(),
                "currentTurnPlayerId", context.actorUserId()
            ),
            occurredAt
        ));

        events.add(createEvent(
            action,
            sequence++,
            EndTurnEventType.EXPIRED_TURN_EFFECTS_CLEARED,
            "MATCH",
            context.currentTurnNumber(),
            Map.of(
                "expiredCount", context.clearedEffectCount(),
                "expiredTurnEffectIds", List.of()
            ),
            occurredAt
        ));

        if (Boolean.TRUE.equals(context.centerReplenishSummary().get("applied"))) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fromZone", "BACK");
            payload.put("toZone", "CENTER");
            payload.put("sourceHolomemId", context.centerReplenishSummary().get("targetHolomemId"));
            payload.put("sourceCardInstanceId", context.centerReplenishSummary().get("targetHolomemCardInstanceId"));
            payload.put("restedAfterMove", context.centerReplenishSummary().get("fromRested"));
            events.add(createEvent(
                action,
                sequence++,
                EndTurnEventType.CENTER_REPLENISHED,
                "BOARD",
                context.currentTurnNumber(),
                payload,
                occurredAt
            ));
        }

        events.add(createEvent(
            action,
            sequence++,
            EndTurnEventType.TURN_ENDED,
            "MATCH",
            context.currentTurnNumber(),
            Map.of(
                "previousTurnPlayerId", context.actorUserId(),
                "endedPhase", context.currentPhase().name()
            ),
            occurredAt
        ));

        events.add(createEvent(
            action,
            sequence,
            EndTurnEventType.TURN_STARTED,
            "MATCH",
            resolutionResult.nextTurnNumber(),
            Map.of(
                "nextTurnPlayerId", resolutionResult.nextTurnPlayerId(),
                "nextTurnNumber", resolutionResult.nextTurnNumber(),
                "nextPhase", MatchPhase.MAIN.name()
            ),
            occurredAt
        ));

        return List.copyOf(events);
    }

    public EndTurnEvent createPendingTurnStartInteractionEvent(
        EndTurnAction action,
        EndTurnResolutionResult resolutionResult,
        Long interactionId
    ) {
        return createEvent(
            action,
            99,
            EndTurnEventType.PENDING_TURN_START_INTERACTION_CREATED,
            "INTERACTION",
            resolutionResult.nextTurnNumber(),
            Map.of(
                "interactionId", interactionId,
                "interactionType", "TURN_START",
                "decisionType", "TURN_START",
                "sourceActionType", "END_TURN"
            ),
            LocalDateTime.now()
        );
    }

    private EndTurnEvent createEvent(
        EndTurnAction action,
        int sequence,
        EndTurnEventType eventType,
        String targetScope,
        int turnNumber,
        Map<String, Object> payload,
        LocalDateTime occurredAt
    ) {
        return new EndTurnEvent(
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
