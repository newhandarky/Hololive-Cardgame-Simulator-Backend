package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainStepGiftFollowupPayloadAppenderTest {

    private final MatchGiftTriggerService matchGiftTriggerService = mock(MatchGiftTriggerService.class);
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder = mock(
        GiftTriggeredEffectDeferredSummaryBuilder.class
    );
    private final SourcelessGiftPendingDecisionCreator sourcelessGiftPendingDecisionCreator = mock(
        SourcelessGiftPendingDecisionCreator.class
    );
    private final MainStepGiftFollowupPayloadAppender appender = new MainStepGiftFollowupPayloadAppender(
        matchGiftTriggerService,
        giftTriggeredEffectDeferredSummaryBuilder,
        sourcelessGiftPendingDecisionCreator,
        new FollowupDecisionPayloadAppender()
    );

    @Test
    void appendShouldAlwaysWriteMainStepGiftEffectsSummary() {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> summary = Map.of("deferred", false, "giftCount", 0);
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnOwnMainStep(100L, 10L, 4)).thenReturn(List.of());
        when(giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(List.of()))
            .thenReturn(summary);

        appender.append(payload, 100L, 10L, 4);

        assertThat(payload)
            .containsEntry("mainStepGiftEffects", summary)
            .doesNotContainKey("pendingInteractionDecisionId")
            .doesNotContainKey("pendingInteractionDecisionType");
        verifyNoInteractions(sourcelessGiftPendingDecisionCreator);
    }

    @Test
    void appendShouldCreatePendingDecisionWhenGiftEffectsExist() {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> giftEffects = List.of(Map.of("triggerType", "MAIN_STEP_SELF"));
        Map<String, Object> summary = Map.of("deferred", true, "giftCount", 1);
        FollowupInteractionDecision decision = new FollowupInteractionDecision(501L, "TRIGGER_EFFECT_CONFIRM");
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnOwnMainStep(100L, 10L, 4)).thenReturn(giftEffects);
        when(giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(giftEffects))
            .thenReturn(summary);
        when(sourcelessGiftPendingDecisionCreator.create(100L, 10L, giftEffects, 4)).thenReturn(decision);

        appender.append(payload, 100L, 10L, 4);

        assertThat(payload)
            .containsEntry("mainStepGiftEffects", summary)
            .containsEntry("pendingInteractionDecisionId", 501L)
            .containsEntry("pendingInteractionDecisionType", "TRIGGER_EFFECT_CONFIRM");
        verify(sourcelessGiftPendingDecisionCreator).create(100L, 10L, giftEffects, 4);
    }
}
