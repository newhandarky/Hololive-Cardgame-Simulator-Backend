package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttackRestAndPayloadResult(
    Map<String, Object> payload,
    Map<String, Object> effectSummaryForChecks
) {
    public AttackRestAndPayloadResult {
        payload = immutableMap(payload);
        effectSummaryForChecks = immutableMap(effectSummaryForChecks);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
