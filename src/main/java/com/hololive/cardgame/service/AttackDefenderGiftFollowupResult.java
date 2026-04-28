package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AttackDefenderGiftFollowupResult(
    Map<String, Object> officialOshiSelfDownedSummary,
    List<Map<String, Object>> defenderGiftTriggeredEffects,
    String downedTargetCardId,
    String downedTargetZone
) {
    public AttackDefenderGiftFollowupResult {
        officialOshiSelfDownedSummary = immutableMap(officialOshiSelfDownedSummary);
        defenderGiftTriggeredEffects = immutableList(defenderGiftTriggeredEffects);
    }

    public boolean hasOfficialOshiSelfDownedSummary() {
        return !officialOshiSelfDownedSummary.isEmpty();
    }

    public boolean hasDefenderGiftTriggeredEffects() {
        return !defenderGiftTriggeredEffects.isEmpty();
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
