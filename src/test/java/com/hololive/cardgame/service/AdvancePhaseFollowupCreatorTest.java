package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdvancePhaseFollowupCreatorTest {

    private final GiftPendingDecisionCreator giftPendingDecisionCreator = mock(GiftPendingDecisionCreator.class);
    private final AdvancePhaseFollowupCreator creator = new AdvancePhaseFollowupCreator(giftPendingDecisionCreator);

    @Test
    void createAdvancePhaseFollowupShouldReturnEmptyWhenTransitionPreviewIsNull() {
        AdvancePhaseFollowup followup = creator.createAdvancePhaseFollowup(
            100L,
            10L,
            20L,
            3,
            null
        );

        assertThat(followup.ownGiftEffects()).isEmpty();
        assertThat(followup.opponentGiftEffects()).isEmpty();
        assertThat(followup.ownDecision()).isNull();
        assertThat(followup.opponentDecision()).isNull();
        verifyNoInteractions(giftPendingDecisionCreator);
    }

    @Test
    void createAdvancePhaseFollowupShouldCreateOwnPendingDecisionWithoutSourceCard() {
        List<Map<String, Object>> ownGiftEffects = List.of(giftTrigger("PERFORMANCE_START_SELF", 701L));
        List<Map<String, Object>> opponentGiftEffects = List.of();
        FollowupInteractionDecision ownDecision = new FollowupInteractionDecision(501L, "TRIGGER_EFFECT_CONFIRM");
        when(giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
            100L,
            10L,
            null,
            null,
            ownGiftEffects,
            3
        )).thenReturn(ownDecision);

        AdvancePhaseFollowup followup = creator.createAdvancePhaseFollowup(
            100L,
            10L,
            null,
            3,
            new MatchPhaseAdvanceGiftTransitionService.GiftTransitionPreview(ownGiftEffects, opponentGiftEffects)
        );

        assertThat(followup.ownGiftEffects()).isEqualTo(ownGiftEffects);
        assertThat(followup.opponentGiftEffects()).isEqualTo(opponentGiftEffects);
        assertThat(followup.ownDecision()).isEqualTo(ownDecision);
        assertThat(followup.opponentDecision()).isNull();
        verify(giftPendingDecisionCreator).createWithGiftTriggerInteractionCards(
            100L,
            10L,
            null,
            null,
            ownGiftEffects,
            3
        );
    }

    @Test
    void createAdvancePhaseFollowupShouldCreateOpponentPendingDecisionWhenOpponentExists() {
        List<Map<String, Object>> ownGiftEffects = List.of(giftTrigger("PERFORMANCE_START_SELF", 701L));
        List<Map<String, Object>> opponentGiftEffects = List.of(giftTrigger("PERFORMANCE_START_OPPONENT", 801L));
        FollowupInteractionDecision ownDecision = new FollowupInteractionDecision(501L, "TRIGGER_EFFECT_CONFIRM");
        FollowupInteractionDecision opponentDecision = new FollowupInteractionDecision(502L, "TRIGGER_EFFECT_CONFIRM");
        when(giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
            100L,
            10L,
            null,
            null,
            ownGiftEffects,
            3
        )).thenReturn(ownDecision);
        when(giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
            100L,
            20L,
            null,
            null,
            opponentGiftEffects,
            3
        )).thenReturn(opponentDecision);

        AdvancePhaseFollowup followup = creator.createAdvancePhaseFollowup(
            100L,
            10L,
            20L,
            3,
            new MatchPhaseAdvanceGiftTransitionService.GiftTransitionPreview(ownGiftEffects, opponentGiftEffects)
        );

        assertThat(followup.ownGiftEffects()).isEqualTo(ownGiftEffects);
        assertThat(followup.opponentGiftEffects()).isEqualTo(opponentGiftEffects);
        assertThat(followup.ownDecision()).isEqualTo(ownDecision);
        assertThat(followup.opponentDecision()).isEqualTo(opponentDecision);
        verify(giftPendingDecisionCreator).createWithGiftTriggerInteractionCards(
            100L,
            20L,
            null,
            null,
            opponentGiftEffects,
            3
        );
    }

    private Map<String, Object> giftTrigger(String triggerType, Long holderCardInstanceId) {
        return Map.of(
            "triggerType",
            triggerType,
            "giftHolderCardInstanceId",
            holderCardInstanceId,
            "requestedEffects",
            List.of("DRAW")
        );
    }
}
