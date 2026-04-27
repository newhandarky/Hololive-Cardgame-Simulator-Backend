package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PlayCardEffectResolution(
    Map<String, Object> triggerSummary,
    List<Map<String, Object>> giftTriggeredEffects,
    Map<String, Object> giftEffectSummary,
    Long pendingInteractionDecisionId,
    String pendingInteractionDecisionType,
    boolean deferredUntilLiveStart,
    List<Map<String, Object>> triggerResolutionOrder
) {

    public PlayCardEffectResolution {
        triggerSummary = copy(triggerSummary);
        giftTriggeredEffects = giftTriggeredEffects == null ? List.of() : List.copyOf(giftTriggeredEffects);
        giftEffectSummary = copy(giftEffectSummary);
        triggerResolutionOrder = triggerResolutionOrder == null ? List.of() : List.copyOf(triggerResolutionOrder);
    }

    public boolean hasPendingInteraction() {
        return pendingInteractionDecisionId != null;
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
