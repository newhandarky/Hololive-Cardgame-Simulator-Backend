package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class GiftTriggerActionPayloadExtractor {

    List<Map<String, Object>> extractTriggeredGiftPayloads(Map<String, Object> effectSummary) {
        if (effectSummary == null) {
            return List.of();
        }
        String sourceActionType = normalize(effectSummary.get("sourceActionType"));
        Object triggeredGifts = effectSummary.get("triggeredGifts");
        if ("ATTACK_ART_POST_TRIGGER".equals(sourceActionType) || "COLLAB".equals(sourceActionType)) {
            Object nestedGift = effectSummary.get("gift");
            if (nestedGift instanceof Map<?, ?> map) {
                triggeredGifts = castToMap(map).get("triggeredGifts");
            }
        } else if (!"GIFT".equals(sourceActionType)) {
            return List.of();
        }
        if (!(triggeredGifts instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof Map<?, ?> map) {
                payloads.add(castToMap(map));
            }
        }
        return payloads;
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> castToMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry != null && entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
}
