package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttackEffectFollowupResult(
    HoloxSlotRevealSummary holoxSlotRevealSummary,
    Map<String, Object> hbp02039SupportRecovery,
    Map<String, Object> hbp02040LifeLoss,
    int artBonus
) {
    public AttackEffectFollowupResult {
        hbp02039SupportRecovery = immutableMap(hbp02039SupportRecovery);
        hbp02040LifeLoss = immutableMap(hbp02040LifeLoss);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
