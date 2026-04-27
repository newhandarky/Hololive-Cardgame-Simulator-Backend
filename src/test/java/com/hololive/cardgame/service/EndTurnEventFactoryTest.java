package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class EndTurnEventFactoryTest {

    private final EndTurnEventFactory eventFactory = new EndTurnEventFactory();
    private final EndTurnTriggerDispatcher triggerDispatcher = new EndTurnTriggerDispatcher(
        List.of(
            new EndTurnSystemStateFinalizationHandler(),
            new EndTurnFollowupInteractionCreationHandler()
        )
    );

    @Test
    void createCoreEventsShouldFollowDocumentedOrderAndAppendPendingInteractionEventLast() {
        EndTurnAction action = new EndTurnAction(
            "END_TURN",
            100L,
            10L,
            4,
            EndTurnAction.ActionSource.TEST,
            "trace-end-turn",
            "idem-end-turn",
            LocalDateTime.now()
        );
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentPhase("END");
        match.setTurnNumber(4);
        match.setCurrentTurnPlayerId(10L);

        var replenishSummary = new LinkedHashMap<String, Object>();
        replenishSummary.put("applied", true);
        replenishSummary.put("targetHolomemId", 901L);
        replenishSummary.put("targetHolomemCardInstanceId", 801L);
        replenishSummary.put("fromRested", false);

        EndTurnContext context = new EndTurnContext(
            match,
            10L,
            20L,
            MatchPhase.END,
            4,
            new EndTurnRequiredActionSummary(true, false, false, List.of()),
            2,
            3,
            replenishSummary
        );
        EndTurnResolutionResult resolutionResult = new EndTurnResolutionResult(
            match,
            10L,
            20L,
            4,
            5,
            MatchPhase.MAIN,
            java.util.Map.of("nextTurnNumber", 5)
        );

        List<EndTurnEvent> coreEvents = eventFactory.createCoreEvents(action, context, resolutionResult);
        EndTurnEvent pendingInteractionEvent = eventFactory.createPendingTurnStartInteractionEvent(
            action,
            resolutionResult,
            1234L
        );

        List<EndTurnEventType> eventTypes = coreEvents.stream().map(EndTurnEvent::eventType).toList();

        assertThat(eventTypes).containsExactly(
            EndTurnEventType.TURN_ENDING,
            EndTurnEventType.EXPIRED_TURN_EFFECTS_CLEARED,
            EndTurnEventType.CENTER_REPLENISHED,
            EndTurnEventType.TURN_ENDED,
            EndTurnEventType.TURN_STARTED
        );
        assertThat(pendingInteractionEvent.eventType()).isEqualTo(EndTurnEventType.PENDING_TURN_START_INTERACTION_CREATED);

        EndTurnTriggerDispatchResult dispatchResult = triggerDispatcher.dispatch(
            java.util.stream.Stream.concat(coreEvents.stream(), java.util.stream.Stream.of(pendingInteractionEvent)).toList()
        );

        assertThat(dispatchResult.dispatchedEvents()).hasSize(6);
        assertThat(dispatchResult.invokedHandlerKeys()).containsExactly(
            "SYSTEM_STATE_FINALIZATION",
            "SYSTEM_STATE_FINALIZATION",
            "SYSTEM_STATE_FINALIZATION",
            "SYSTEM_STATE_FINALIZATION",
            "SYSTEM_STATE_FINALIZATION",
            "FOLLOWUP_INTERACTION_CREATION"
        );
        assertThat(dispatchResult.handlingResults())
            .extracting(EndTurnTriggerHandlingResult::executionMode)
            .containsExactly(
                EndTurnTriggerExecutionMode.SYNC,
                EndTurnTriggerExecutionMode.SYNC,
                EndTurnTriggerExecutionMode.SYNC,
                EndTurnTriggerExecutionMode.SYNC,
                EndTurnTriggerExecutionMode.SYNC,
                EndTurnTriggerExecutionMode.DEFERRED
            );
    }
}
