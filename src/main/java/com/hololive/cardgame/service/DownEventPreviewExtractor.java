package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class DownEventPreviewExtractor {

    Map<String, Object> extractDownEventPreview(Map<String, Object> effectSummary) {
        if (effectSummary == null || effectSummary.isEmpty()) {
            return null;
        }
        Object downEvent = effectSummary.get("downEvent");
        if (downEvent instanceof Map<?, ?> map) {
            Map<String, Object> preview = castToMap(map);
            if (toBoolean(preview.get("triggered")) && toBoolean(preview.get("deferred"))) {
                return preview;
            }
        }
        Object executedEffects = effectSummary.get("executedEffects");
        if (!(executedEffects instanceof List<?> list)) {
            return null;
        }
        for (Object effect : list) {
            if (!(effect instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> nested = extractDownEventPreview(castToMap(map));
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private Map<String, Object> castToMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
