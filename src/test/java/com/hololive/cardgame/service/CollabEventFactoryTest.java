package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollabEventFactoryTest {

    private final CollabEventFactory eventFactory = new CollabEventFactory();
    private final CollabTriggerDispatcher triggerDispatcher = new CollabTriggerDispatcher(
        List.of(
            new CollabSystemStateFinalizationHandler(),
            new CollabEffectPreviewHandler(),
            new CollabGiftPreviewHandler(),
            new CollabTriggerConfirmHandler()
        )
    );

    @Test
    void createEventsShouldFollowDocumentedOrderAndDeferConfirmInteraction() {
        CollabAction action = action();
        CollabResolutionResult resolutionResult = resolutionResult();
        CollabEffectResolution effectResolution = new CollabEffectResolution(
            new MatchEffectService.TriggeredEffectPreview(true, List.of("LOOK_TOP_DECK"), "raw", null),
            Map.of("hasCollabEffect", true, "deferred", true),
            List.of(Map.of("triggerType", "COLLAB", "requestedEffects", List.of("DRAW"))),
            Map.of("sourceActionType", "GIFT", "deferred", true),
            Map.of("triggered", true),
            1234L,
            "TRIGGER_EFFECT_CONFIRM",
            List.of()
        );

        List<CollabEvent> events = eventFactory.createEvents(action, resolutionResult, effectResolution);

        assertThat(events).extracting(CollabEvent::eventType).containsExactly(
            CollabEventType.COLLAB_REQUEST_ACCEPTED,
            CollabEventType.COLLAB_RESOLVED,
            CollabEventType.COLLAB_EFFECT_PREVIEW_CREATED,
            CollabEventType.COLLAB_GIFT_PREVIEW_CREATED,
            CollabEventType.COLLAB_TRIGGER_CONFIRM_REQUIRED
        );
        assertThat(events.get(1).targetScope()).isEqualTo("BOARD");
        assertThat(events.get(3).payload()).containsEntry("triggerCount", 1);
        assertThat(events.get(4).payload()).containsEntry("interactionId", 1234L);

        CollabTriggerDispatchResult dispatchResult = triggerDispatcher.dispatch(events);

        assertThat(dispatchResult.invokedHandlerKeys()).containsExactly(
            "COLLAB_SYSTEM_STATE_FINALIZATION",
            "COLLAB_SYSTEM_STATE_FINALIZATION",
            "COLLAB_EFFECT_PREVIEW",
            "COLLAB_GIFT_PREVIEW",
            "COLLAB_TRIGGER_CONFIRM"
        );
        assertThat(dispatchResult.handlingResults())
            .extracting(CollabTriggerHandlingResult::executionMode)
            .containsExactly(
                CollabTriggerExecutionMode.SYNC,
                CollabTriggerExecutionMode.SYNC,
                CollabTriggerExecutionMode.SYNC,
                CollabTriggerExecutionMode.SYNC,
                CollabTriggerExecutionMode.DEFERRED
            );
    }

    @Test
    void createEventsShouldSkipOptionalPreviewAndConfirmEventsWhenNoFollowupExists() {
        CollabEffectResolution effectResolution = new CollabEffectResolution(
            new MatchEffectService.TriggeredEffectPreview(false, List.of(), null, null),
            Map.of("hasCollabEffect", false, "deferred", false),
            List.of(),
            Map.of(),
            Map.of(),
            null,
            null,
            List.of()
        );

        List<CollabEvent> events = eventFactory.createEvents(action(), resolutionResult(), effectResolution);

        assertThat(events).extracting(CollabEvent::eventType).containsExactly(
            CollabEventType.COLLAB_REQUEST_ACCEPTED,
            CollabEventType.COLLAB_RESOLVED
        );
    }

    private CollabAction action() {
        return new CollabAction(
            "COLLAB",
            100L,
            10L,
            701L,
            "COLLAB",
            4,
            CollabAction.ActionSource.TEST,
            "trace-collab",
            "idem-collab",
            LocalDateTime.now()
        );
    }

    private CollabResolutionResult resolutionResult() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        return new CollabResolutionResult(
            match,
            10L,
            4,
            901L,
            701L,
            "hBP01-001",
            "BACK",
            "COLLAB",
            601L
        );
    }
}
