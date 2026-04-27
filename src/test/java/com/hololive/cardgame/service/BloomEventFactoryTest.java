package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BloomEventFactoryTest {

    private final BloomEventFactory eventFactory = new BloomEventFactory();
    private final BloomTriggerDispatcher triggerDispatcher = new BloomTriggerDispatcher(
        List.of(
            new BloomSystemStateFinalizationHandler(),
            new BloomEffectPreviewHandler(),
            new BloomTriggerConfirmHandler()
        )
    );

    @Test
    void createEventsShouldFollowDocumentedOrderAndDeferConfirmInteraction() {
        BloomAction action = new BloomAction(
            "BLOOM",
            100L,
            10L,
            701L,
            801L,
            4,
            BloomAction.ActionSource.TEST,
            "trace-bloom",
            "idem-bloom",
            LocalDateTime.now()
        );
        MatchEntity match = new MatchEntity();
        match.setId(100L);

        BloomResolutionResult resolutionResult = new BloomResolutionResult(
            match,
            10L,
            4,
            701L,
            "hBP01-002",
            "FIRST",
            901L,
            801L,
            "hBP01-001",
            "DEBUT",
            "CENTER",
            10,
            2,
            false,
            null,
            Map.of("applied", false),
            Map.of("hasEffect", true, "effectType", "BLOOM_EFFECT"),
            Map.of("triggered", false),
            1234L
        );

        List<BloomEvent> events = eventFactory.createEvents(action, resolutionResult);

        assertThat(events).extracting(BloomEvent::eventType).containsExactly(
            BloomEventType.BLOOM_REQUEST_ACCEPTED,
            BloomEventType.BLOOM_RESOLVED,
            BloomEventType.BLOOM_EFFECT_PREVIEW_CREATED,
            BloomEventType.BLOOM_TRIGGER_CONFIRM_REQUIRED
        );
        assertThat(events.get(1).targetScope()).isEqualTo("BOARD");
        assertThat(events.get(3).payload()).containsEntry("interactionId", 1234L);

        BloomTriggerDispatchResult dispatchResult = triggerDispatcher.dispatch(events);

        assertThat(dispatchResult.invokedHandlerKeys()).containsExactly(
            "BLOOM_SYSTEM_STATE_FINALIZATION",
            "BLOOM_SYSTEM_STATE_FINALIZATION",
            "BLOOM_EFFECT_PREVIEW",
            "BLOOM_TRIGGER_CONFIRM"
        );
        assertThat(dispatchResult.handlingResults())
            .extracting(BloomTriggerHandlingResult::executionMode)
            .containsExactly(
                BloomTriggerExecutionMode.SYNC,
                BloomTriggerExecutionMode.SYNC,
                BloomTriggerExecutionMode.SYNC,
                BloomTriggerExecutionMode.DEFERRED
            );
    }
}
