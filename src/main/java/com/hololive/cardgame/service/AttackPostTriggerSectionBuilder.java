package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class AttackPostTriggerSectionBuilder {

    List<Map<String, Object>> buildAttackArtPostTriggerSections(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview
    ) {
        List<Map<String, Object>> sections = new ArrayList<>();
        if (downEventPreview != null && !downEventPreview.isEmpty()) {
            Map<String, Object> downSection = new LinkedHashMap<>();
            downSection.put("sectionType", "DOWN_EVENT");
            downSection.put("title", "Down Event");
            downSection.put("requestedLifeLoss", asInt(downEventPreview.get("requestedLifeLoss")));
            downSection.put("downedCardId", asString(downEventPreview.get("downedCardId")));
            downSection.put("rawText", asString(downEventPreview.get("rawText")));
            sections.add(downSection);
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            List<Map<String, Object>> giftItems = new ArrayList<>();
            for (Map<String, Object> trigger : giftTriggeredEffects) {
                if (trigger == null || trigger.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("triggerType", normalize(trigger.get("triggerType")));
                item.put("giftHolderCardId", asString(trigger.get("giftHolderCardId")));
                item.put("rawText", asString(trigger.get("rawText")));
                item.put("requestedEffects", toStringList(trigger.get("requestedEffects")));
                giftItems.add(item);
            }
            if (!giftItems.isEmpty()) {
                Map<String, Object> giftSection = new LinkedHashMap<>();
                giftSection.put("sectionType", "GIFT");
                giftSection.put("title", "Gift");
                giftSection.put("count", giftItems.size());
                giftSection.put("items", giftItems);
                sections.add(giftSection);
            }
        }
        return sections;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = normalize(item);
            if (!hasText(text) || result.contains(text)) {
                continue;
            }
            result.add(text);
        }
        return result;
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
