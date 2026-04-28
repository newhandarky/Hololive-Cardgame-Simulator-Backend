package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttackEffectDamagePreventionResult(
    Map<String, Object> defenderDamageReceivedGiftSummary,
    int adjustedDamage,
    boolean actionLogRequired
) {
    public AttackEffectDamagePreventionResult {
        defenderDamageReceivedGiftSummary = immutableMap(defenderDamageReceivedGiftSummary);
    }

    public static AttackEffectDamagePreventionResult unchanged(int totalDamage) {
        return new AttackEffectDamagePreventionResult(Map.of(), totalDamage, false);
    }

    public static AttackEffectDamagePreventionResult resolved(Map<String, Object> summary, int adjustedDamage) {
        return new AttackEffectDamagePreventionResult(summary, adjustedDamage, true);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
