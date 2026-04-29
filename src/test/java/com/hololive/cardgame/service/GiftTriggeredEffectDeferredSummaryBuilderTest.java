package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GiftTriggeredEffectDeferredSummaryBuilderTest {

    private final GiftTriggeredEffectDeferredSummaryBuilder builder = new GiftTriggeredEffectDeferredSummaryBuilder();

    @Test
    void buildGiftTriggeredEffectDeferredSummaryShouldReturnEmptyDeferredSummaryWhenInputIsEmpty() {
        assertThat(builder.buildGiftTriggeredEffectDeferredSummary(null))
            .containsEntry("sourceActionType", "GIFT")
            .containsEntry("deferred", false)
            .containsEntry("triggeredGifts", List.of())
            .containsEntry("requestedEffects", List.of())
            .containsEntry("executedEffects", List.of())
            .containsEntry("unsupportedEffects", List.of());

        assertThat(builder.buildGiftTriggeredEffectDeferredSummary(List.of()))
            .containsEntry("sourceActionType", "GIFT")
            .containsEntry("deferred", false)
            .containsEntry("triggeredGifts", List.of())
            .containsEntry("requestedEffects", List.of())
            .containsEntry("executedEffects", List.of())
            .containsEntry("unsupportedEffects", List.of());
    }

    @Test
    void buildGiftTriggeredEffectDeferredSummaryShouldNormalizeRequestedEffects() {
        Map<String, Object> drawTrigger = Map.of("requestedEffects", List.of("draw", " DRAW ", "", "damage"));
        Map<String, Object> duplicateTrigger = Map.of("requestedEffects", List.of("DRAW", "heal"));
        Map<String, Object> ignoredTrigger = Map.of("requestedEffects", "bad");
        List<Map<String, Object>> triggers = new ArrayList<>(List.of(drawTrigger, duplicateTrigger, ignoredTrigger));
        triggers.add(null);

        Map<String, Object> summary = builder.buildGiftTriggeredEffectDeferredSummary(triggers);

        assertThat(summary)
            .containsEntry("sourceActionType", "GIFT")
            .containsEntry("deferred", true)
            .containsEntry("triggeredGifts", triggers)
            .containsEntry("requestedEffects", List.of("DRAW", "DAMAGE", "HEAL"))
            .containsEntry("executedEffects", List.of())
            .containsEntry("unsupportedEffects", List.of());
    }
}
