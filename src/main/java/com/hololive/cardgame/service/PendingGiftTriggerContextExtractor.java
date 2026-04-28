package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class PendingGiftTriggerContextExtractor {

    List<Map<String, Object>> extractGiftTriggerContexts(JsonNode contextNode) {
        if (contextNode == null || contextNode.isNull()) {
            return List.of();
        }
        JsonNode triggersNode = contextNode.get("giftTriggers");
        if (triggersNode == null || !triggersNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> triggers = new ArrayList<>();
        for (JsonNode node : triggersNode) {
            if (node == null || !node.isObject()) {
                continue;
            }
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("triggerType", extractJsonText(node, "triggerType"));
            trigger.put("sourceCardInstanceId", extractJsonLong(node, "sourceCardInstanceId"));
            trigger.put("triggerTargetCardInstanceId", extractJsonLong(node, "triggerTargetCardInstanceId"));
            trigger.put("giftHolderHolomemId", extractJsonLong(node, "giftHolderHolomemId"));
            trigger.put("giftHolderCardInstanceId", extractJsonLong(node, "giftHolderCardInstanceId"));
            trigger.put("giftHolderCardId", extractJsonText(node, "giftHolderCardId"));
            trigger.put("giftHolderZone", extractJsonText(node, "giftHolderZone"));
            trigger.put(
                "giftHolderAttachedCheerCardInstanceIds",
                extractJsonLongList(node, "giftHolderAttachedCheerCardInstanceIds")
            );
            trigger.put(
                "giftHolderAttachedCheerCardIds",
                extractJsonTextList(node, "giftHolderAttachedCheerCardIds")
            );
            trigger.put(
                "giftHolderStackCardInstanceIds",
                extractJsonLongList(node, "giftHolderStackCardInstanceIds")
            );
            trigger.put(
                "giftHolderStackCardIds",
                extractJsonTextList(node, "giftHolderStackCardIds")
            );
            trigger.put("selectionRequired", extractJsonBoolean(node, "selectionRequired"));
            trigger.put("selectionEffectType", extractJsonText(node, "selectionEffectType"));
            trigger.put("selectionMinSelect", extractJsonLong(node, "selectionMinSelect"));
            trigger.put("selectionMaxSelect", extractJsonLong(node, "selectionMaxSelect"));
            trigger.put(
                "selectionCandidateCardInstanceIds",
                extractJsonLongList(node, "selectionCandidateCardInstanceIds")
            );
            trigger.put("rawText", extractJsonText(node, "rawText"));
            triggers.add(trigger);
        }
        return triggers;
    }

    private Long extractJsonLong(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !hasText(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractJsonText(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !hasText(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private boolean extractJsonBoolean(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !hasText(fieldName)) {
            return false;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            return Boolean.parseBoolean(value.asText());
        }
        return false;
    }

    private List<Long> extractJsonLongList(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !hasText(fieldName)) {
            return List.of();
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode item : value) {
            Long id = null;
            if (item != null && item.isNumber()) {
                id = item.longValue();
            } else if (item != null && item.isTextual()) {
                try {
                    id = Long.parseLong(item.asText().trim());
                } catch (NumberFormatException ignored) {
                    id = null;
                }
            }
            if (id == null || id <= 0 || result.contains(id)) {
                continue;
            }
            result.add(id);
        }
        return result;
    }

    private List<String> extractJsonTextList(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !hasText(fieldName)) {
            return List.of();
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item == null || item.isNull()) {
                continue;
            }
            String text = normalize(item.asText());
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
