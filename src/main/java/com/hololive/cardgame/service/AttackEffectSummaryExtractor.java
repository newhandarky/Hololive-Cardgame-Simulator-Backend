package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class AttackEffectSummaryExtractor {

    List<Map<String, Object>> extractExecutedEffectSummaries(Map<String, Object> effectSummary) {
        if (effectSummary == null || effectSummary.isEmpty()) {
            return List.of();
        }
        Object executed = effectSummary.get("executedEffects");
        if (!(executed instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Object effect : list) {
            if (effect instanceof Map<?, ?> effectMap) {
                Map<String, Object> casted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : effectMap.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    casted.put(entry.getKey().toString(), entry.getValue());
                }
                summaries.add(casted);
            }
        }
        return summaries;
    }
}
