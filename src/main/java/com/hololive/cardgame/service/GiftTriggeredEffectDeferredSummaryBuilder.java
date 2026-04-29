package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

class GiftTriggeredEffectDeferredSummaryBuilder {

    Map<String, Object> buildGiftTriggeredEffectDeferredSummary(List<Map<String, Object>> giftTriggeredEffects) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> triggers = giftTriggeredEffects == null ? List.of() : giftTriggeredEffects;
        List<String> requestedEffects = new ArrayList<>();
        for (Map<String, Object> trigger : triggers) {
            Object requested = trigger.get("requestedEffects");
            if (!(requested instanceof List<?> list)) {
                continue;
            }
            for (Object effectType : list) {
                String normalized = normalize(effectType);
                if (StringUtils.hasText(normalized) && !requestedEffects.contains(normalized)) {
                    requestedEffects.add(normalized);
                }
            }
        }
        summary.put("sourceActionType", "GIFT");
        summary.put("deferred", !triggers.isEmpty());
        summary.put("triggeredGifts", triggers);
        summary.put("requestedEffects", requestedEffects);
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        return summary;
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }
}
