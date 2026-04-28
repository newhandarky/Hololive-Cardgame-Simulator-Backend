package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttackArtApplicationResult(
    Map<String, Object> stageResults,
    Map<String, Object> payload,
    Object actionLogResult,
    Object finishCheckResult
) {
    public AttackArtApplicationResult {
        stageResults = immutableMap(stageResults);
        payload = immutableMap(payload);
    }

    public Object stageResult(String stageName) {
        return stageResults.get(stageName);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
