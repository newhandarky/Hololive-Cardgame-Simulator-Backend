package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayCardEventFactoryTest {

    private final PlayCardEventFactory eventFactory = new PlayCardEventFactory();
    private final PlayCardTriggerDispatcher triggerDispatcher = new PlayCardTriggerDispatcher(
        List.of(
            new PlayCardSystemStateFinalizationHandler(),
            new PlayCardEnterHookHandler(),
            new PlayCardGiftPreviewHandler(),
            new PlayCardGiftConfirmHandler()
        )
    );

    @Test
    void createEventsShouldFollowDocumentedOrderAndDeferGiftConfirm() {
        PlayCardEffectResolution effectResolution = new PlayCardEffectResolution(
            Map.of("triggered", true),
            List.of(Map.of("triggerType", "STAGE_ENTER", "requestedEffects", List.of("DRAW"))),
            Map.of("sourceActionType", "GIFT", "deferred", true),
            1234L,
            "TRIGGER_EFFECT_CONFIRM",
            false,
            List.of()
        );

        List<PlayCardEvent> events = eventFactory.createEvents(action(false), resolutionResult(false), effectResolution);

        assertThat(events).extracting(PlayCardEvent::eventType).containsExactly(
            PlayCardEventType.PLAY_CARD_REQUEST_ACCEPTED,
            PlayCardEventType.PLAY_CARD_RESOLVED,
            PlayCardEventType.PLAY_CARD_ENTER_HOOK_RESOLVED,
            PlayCardEventType.PLAY_CARD_GIFT_PREVIEW_CREATED,
            PlayCardEventType.PLAY_CARD_GIFT_CONFIRM_REQUIRED
        );
        assertThat(events.get(1).targetScope()).isEqualTo("BOARD");
        assertThat(events.get(3).payload()).containsEntry("giftTriggerCount", 1);
        assertThat(events.get(4).payload()).containsEntry("decisionId", 1234L);
        assertThat(events.get(4).payload()).containsEntry("triggerType", "STAGE_ENTER");

        PlayCardTriggerDispatchResult dispatchResult = triggerDispatcher.dispatch(events);

        assertThat(dispatchResult.invokedHandlerKeys()).containsExactly(
            "PLAY_CARD_SYSTEM_STATE_FINALIZATION",
            "PLAY_CARD_SYSTEM_STATE_FINALIZATION",
            "PLAY_CARD_ENTER_HOOK",
            "PLAY_CARD_GIFT_PREVIEW",
            "PLAY_CARD_GIFT_CONFIRM"
        );
        assertThat(dispatchResult.handlingResults())
            .extracting(PlayCardTriggerHandlingResult::executionMode)
            .containsExactly(
                PlayCardTriggerExecutionMode.SYNC,
                PlayCardTriggerExecutionMode.SYNC,
                PlayCardTriggerExecutionMode.SYNC,
                PlayCardTriggerExecutionMode.SYNC,
                PlayCardTriggerExecutionMode.DEFERRED
            );
    }

    @Test
    void createEventsShouldOnlyEmitAcceptedAndResolvedDuringOpeningReset() {
        PlayCardEffectResolution effectResolution = new PlayCardEffectResolution(
            Map.of("deferredUntilLiveStart", true),
            List.of(),
            Map.of(),
            null,
            null,
            true,
            List.of()
        );

        List<PlayCardEvent> events = eventFactory.createEvents(action(true), resolutionResult(true), effectResolution);

        assertThat(events).extracting(PlayCardEvent::eventType).containsExactly(
            PlayCardEventType.PLAY_CARD_REQUEST_ACCEPTED,
            PlayCardEventType.PLAY_CARD_RESOLVED
        );
        assertThat(events.get(1).payload()).containsEntry("deferredUntilLiveStart", true);
    }

    private PlayCardAction action(boolean openingReset) {
        return new PlayCardAction(
            "PLAY_CARD",
            100L,
            10L,
            701L,
            "BACK",
            4,
            openingReset,
            PlayCardAction.ActionSource.TEST,
            "trace-play-card",
            "idem-play-card",
            LocalDateTime.now()
        );
    }

    private PlayCardResolutionResult resolutionResult(boolean openingReset) {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        return new PlayCardResolutionResult(
            match,
            10L,
            4,
            701L,
            "hBP01-001",
            "HAND",
            "BACK",
            501L,
            4,
            openingReset,
            "DEBUT",
            openingReset
        );
    }
}
