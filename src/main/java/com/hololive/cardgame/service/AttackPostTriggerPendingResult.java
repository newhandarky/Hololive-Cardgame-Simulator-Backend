package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttackPostTriggerPendingResult(
    Map<String, Object> postTriggerEffectSummary,
    AttackPendingDecision postTriggerConfirmDecision,
    Map<String, Object> defenderGiftEffectSummary,
    AttackPendingDecision defenderGiftConfirmDecision
) {
    public AttackPostTriggerPendingResult {
        postTriggerEffectSummary = immutableMap(postTriggerEffectSummary);
        defenderGiftEffectSummary = immutableMap(defenderGiftEffectSummary);
    }

    public boolean hasPostTriggerPendingInteraction() {
        return postTriggerConfirmDecision != null && postTriggerConfirmDecision.hasDecision();
    }

    public boolean hasDefenderGiftPendingInteraction() {
        return defenderGiftConfirmDecision != null && defenderGiftConfirmDecision.hasDecision();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
