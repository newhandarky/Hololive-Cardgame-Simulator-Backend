package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

class PendingDownEventContextExtractor {

    Map<String, Object> extractDownEventContext(JsonNode contextNode) {
        if (contextNode == null || contextNode.isNull()) {
            return null;
        }
        JsonNode downEventNode = contextNode.get("downEvent");
        if (downEventNode == null || downEventNode.isNull() || !downEventNode.isObject()) {
            return null;
        }
        Map<String, Object> downEvent = new LinkedHashMap<>();
        downEvent.put("downedOwnerUserId", extractJsonLong(downEventNode, "downedOwnerUserId"));
        downEvent.put("downedCardId", extractJsonText(downEventNode, "downedCardId"));
        downEvent.put("downedStageZone", extractJsonText(downEventNode, "downedStageZone"));
        downEvent.put("turnNumber", asInt(extractJsonLong(downEventNode, "turnNumber")));
        downEvent.put("rawText", extractJsonText(downEventNode, "rawText"));
        downEvent.put("requestedLifeLoss", asInt(extractJsonLong(downEventNode, "requestedLifeLoss")));
        return downEvent;
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
