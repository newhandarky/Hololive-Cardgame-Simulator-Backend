package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BatonTouchGiftFollowupCreatorTest {

    private final MatchGiftTriggerService matchGiftTriggerService = mock(MatchGiftTriggerService.class);
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder = mock(
        GiftTriggeredEffectDeferredSummaryBuilder.class
    );
    private final GiftPendingDecisionCreator giftPendingDecisionCreator = mock(GiftPendingDecisionCreator.class);
    private final BatonTouchGiftFollowupCreator creator = new BatonTouchGiftFollowupCreator(
        matchGiftTriggerService,
        giftTriggeredEffectDeferredSummaryBuilder,
        giftPendingDecisionCreator
    );

    @Test
    void createShouldReturnEmptyWhenNoGiftEffects() {
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnBatonTouchBack(100L, 10L, 701L, 4))
            .thenReturn(List.of());

        BatonTouchGiftFollowup followup = creator.create(100L, 10L, 701L, "hBP01-001", 4);

        assertThat(followup.hasGiftEffects()).isFalse();
        assertThat(followup.giftEffectSummary()).isNull();
        assertThat(followup.decision()).isNull();
        verifyNoInteractions(giftTriggeredEffectDeferredSummaryBuilder, giftPendingDecisionCreator);
    }

    @Test
    void createShouldBuildSummaryAndSourceCardPendingDecisionWhenGiftEffectsExist() {
        List<Map<String, Object>> giftEffects = List.of(Map.of("triggerType", "BATON_TOUCH_BACK"));
        Map<String, Object> summary = Map.of("deferred", true, "giftCount", 1);
        FollowupInteractionDecision decision = new FollowupInteractionDecision(501L, "TRIGGER_EFFECT_CONFIRM");
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnBatonTouchBack(100L, 10L, 701L, 4))
            .thenReturn(giftEffects);
        when(giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(giftEffects))
            .thenReturn(summary);
        when(giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
            100L,
            10L,
            701L,
            "hBP01-001",
            giftEffects,
            4
        )).thenReturn(decision);

        BatonTouchGiftFollowup followup = creator.create(100L, 10L, 701L, "hBP01-001", 4);

        assertThat(followup.hasGiftEffects()).isTrue();
        assertThat(followup.giftEffectSummary()).isEqualTo(summary);
        assertThat(followup.decision()).isEqualTo(decision);
        verify(giftPendingDecisionCreator).createWithGiftTriggerInteractionCards(
            100L,
            10L,
            701L,
            "hBP01-001",
            giftEffects,
            4
        );
    }
}
