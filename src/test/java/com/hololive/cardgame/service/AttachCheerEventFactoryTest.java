package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttachCheerEventFactoryTest {

    private final AttachCheerEventFactory eventFactory = new AttachCheerEventFactory();
    private final AttachCheerTriggerDispatcher triggerDispatcher = new AttachCheerTriggerDispatcher(
        List.of(new AttachCheerSystemStateFinalizationHandler())
    );

    @Test
    void createEventsShouldFollowDocumentedOrderAndDispatchSynchronously() {
        AttachCheerAction action = action();
        AttachCheerResolutionResult resolutionResult = resolutionResult();

        List<AttachCheerEvent> events = eventFactory.createEvents(action, resolutionResult);

        assertThat(events).extracting(AttachCheerEvent::eventType).containsExactly(
            AttachCheerEventType.ATTACH_CHEER_REQUEST_ACCEPTED,
            AttachCheerEventType.ATTACH_CHEER_RESOLVED
        );
        assertThat(events.get(0).targetScope()).isEqualTo("PLAYER");
        assertThat(events.get(1).targetScope()).isEqualTo("BOARD");
        assertThat(events.get(1).payload()).containsEntry("attachmentId", 501L);
        assertThat(events.get(1).payload()).containsEntry("sourceZone", "CHEER_DECK");

        AttachCheerTriggerDispatchResult dispatchResult = triggerDispatcher.dispatch(events);

        assertThat(dispatchResult.invokedHandlerKeys()).containsExactly(
            "ATTACH_CHEER_SYSTEM_STATE_FINALIZATION",
            "ATTACH_CHEER_SYSTEM_STATE_FINALIZATION"
        );
        assertThat(dispatchResult.handlingResults())
            .extracting(AttachCheerTriggerHandlingResult::executionMode)
            .containsExactly(
                AttachCheerTriggerExecutionMode.SYNC,
                AttachCheerTriggerExecutionMode.SYNC
            );
    }

    private AttachCheerAction action() {
        return new AttachCheerAction(
            "ATTACH_CHEER",
            100L,
            10L,
            701L,
            801L,
            4,
            AttachCheerAction.ActionSource.TEST,
            "trace-attach-cheer",
            "idem-attach-cheer",
            LocalDateTime.now()
        );
    }

    private AttachCheerResolutionResult resolutionResult() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        return new AttachCheerResolutionResult(
            match,
            10L,
            4,
            701L,
            "hY01-001",
            "CHEER_DECK",
            901L,
            801L,
            501L
        );
    }
}
