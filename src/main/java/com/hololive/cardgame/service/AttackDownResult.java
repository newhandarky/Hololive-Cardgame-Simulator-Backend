package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AttackDownResult(
    Map<String, Object> attackSummaryForTriggeredChecks,
    boolean hasDownedHolomem,
    List<Map<String, Object>> giftTriggeredEffects,
    Map<String, Object> artDownTriggeredEffectSummary,
    Map<String, Object> downEventPreview
) {
    public AttackDownResult {
        attackSummaryForTriggeredChecks = immutableMap(attackSummaryForTriggeredChecks);
        giftTriggeredEffects = immutableList(giftTriggeredEffects);
        artDownTriggeredEffectSummary = immutableMap(artDownTriggeredEffectSummary);
        downEventPreview = downEventPreview == null ? null : immutableMap(downEventPreview);
    }

    public boolean hasGiftTriggeredEffects() {
        return !giftTriggeredEffects.isEmpty();
    }

    public boolean hasDeferredDownEvent() {
        return downEventPreview != null && !downEventPreview.isEmpty();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static List<Map<String, Object>> immutableList(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
