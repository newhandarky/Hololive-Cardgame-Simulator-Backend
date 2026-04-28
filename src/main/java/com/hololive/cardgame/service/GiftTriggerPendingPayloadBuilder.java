package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class GiftTriggerPendingPayloadBuilder {

    List<Map<String, Object>> buildGiftTriggerPayloads(List<Map<String, Object>> giftTriggeredEffects) {
        List<Map<String, Object>> giftTriggers = new ArrayList<>();
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return giftTriggers;
        }
        for (Map<String, Object> trigger : giftTriggeredEffects) {
            if (trigger == null || trigger.isEmpty()) {
                continue;
            }
            Map<String, Object> triggerPayload = new LinkedHashMap<>();
            triggerPayload.put("triggerType", normalize(trigger.get("triggerType")));
            triggerPayload.put("sourceCardInstanceId", asLong(trigger.get("sourceCardInstanceId")));
            triggerPayload.put("triggerTargetCardInstanceId", asLong(trigger.get("triggerTargetCardInstanceId")));
            triggerPayload.put("giftHolderHolomemId", asLong(trigger.get("giftHolderHolomemId")));
            triggerPayload.put("giftHolderCardInstanceId", asLong(trigger.get("giftHolderCardInstanceId")));
            triggerPayload.put("giftHolderCardId", asString(trigger.get("giftHolderCardId")));
            triggerPayload.put("giftHolderZone", asString(trigger.get("giftHolderZone")));
            triggerPayload.put(
                "giftHolderAttachedCheerCardInstanceIds",
                toLongList(trigger.get("giftHolderAttachedCheerCardInstanceIds"))
            );
            triggerPayload.put("giftHolderAttachedCheerCardIds", toStringList(trigger.get("giftHolderAttachedCheerCardIds")));
            triggerPayload.put("giftHolderStackCardInstanceIds", toLongList(trigger.get("giftHolderStackCardInstanceIds")));
            triggerPayload.put("giftHolderStackCardIds", toStringList(trigger.get("giftHolderStackCardIds")));
            triggerPayload.put("selectionRequired", toBoolean(trigger.get("selectionRequired")));
            triggerPayload.put("selectionEffectType", asString(trigger.get("selectionEffectType")));
            triggerPayload.put("selectionMinSelect", asInt(trigger.get("selectionMinSelect")));
            triggerPayload.put("selectionMaxSelect", asInt(trigger.get("selectionMaxSelect")));
            triggerPayload.put(
                "selectionCandidateCardInstanceIds",
                toLongList(trigger.get("selectionCandidateCardInstanceIds"))
            );
            triggerPayload.put("rawText", asString(trigger.get("rawText")));
            giftTriggers.add(triggerPayload);
        }
        return giftTriggers;
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

    private List<Long> toLongList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            Long id = asLong(item);
            if (id == null || id <= 0 || result.contains(id)) {
                continue;
            }
            result.add(id);
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

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
