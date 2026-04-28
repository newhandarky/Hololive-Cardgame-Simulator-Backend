package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AttackEffectPostDamageResult(
    Map<String, Object> officialCardArtExtraSummary,
    List<Map<String, Object>> officialCardArtExtraEffects,
    Map<String, Object> officialOshiArtReactiveSummary,
    List<Map<String, Object>> officialOshiArtReactiveEffects
) {
    public AttackEffectPostDamageResult {
        officialCardArtExtraSummary = immutableMap(officialCardArtExtraSummary);
        officialCardArtExtraEffects = immutableList(officialCardArtExtraEffects);
        officialOshiArtReactiveSummary = immutableMap(officialOshiArtReactiveSummary);
        officialOshiArtReactiveEffects = immutableList(officialOshiArtReactiveEffects);
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
        List<Map<String, Object>> copied = new ArrayList<>();
        for (Map<String, Object> item : source) {
            copied.add(immutableMap(item));
        }
        return Collections.unmodifiableList(copied);
    }
}
