package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CollabEffectResolution(
    MatchEffectService.TriggeredEffectPreview collabPreview,
    Map<String, Object> collabEffectSummary,
    List<Map<String, Object>> giftTriggeredEffects,
    Map<String, Object> giftEffectSummary,
    Map<String, Object> triggerSummary,
    Long pendingInteractionDecisionId,
    String pendingInteractionDecisionType,
    List<Map<String, Object>> triggerResolutionOrder
) {

    public CollabEffectResolution {
        collabEffectSummary = copy(collabEffectSummary);
        giftTriggeredEffects = giftTriggeredEffects == null ? List.of() : List.copyOf(giftTriggeredEffects);
        giftEffectSummary = copy(giftEffectSummary);
        triggerSummary = copy(triggerSummary);
        triggerResolutionOrder = triggerResolutionOrder == null ? List.of() : List.copyOf(triggerResolutionOrder);
    }

    public boolean hasPendingInteraction() {
        return pendingInteractionDecisionId != null;
    }

    public boolean hasImmediateEffectSummary() {
        return collabEffectSummary != null && !collabEffectSummary.isEmpty();
    }

    public boolean hasDeferredCollabEffect() {
        return collabPreview != null && collabPreview.hasEffect();
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
