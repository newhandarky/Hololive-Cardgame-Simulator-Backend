package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class MatchEffectValueHelper {

    private MatchEffectValueHelper() {
    }

    static String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    static String asText(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    static boolean toBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if (normalized.isEmpty()) {
                return false;
            }
            return "1".equals(normalized) || "true".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized);
        }
        return false;
    }

    static String readText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    static Boolean readBoolean(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isInt() || value.isLong()) {
                return value.asInt() != 0;
            }
            if (value.isTextual()) {
                String normalized = value.asText().trim().toLowerCase(Locale.ROOT);
                if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
                    return true;
                }
                if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
                    return false;
                }
            }
        }
        return null;
    }

    static Long readLong(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isIntegralNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                String text = value.asText();
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException ignored) {
                    // ignore invalid numeric token
                }
            }
        }
        return null;
    }

    static Boolean readRowBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
                return false;
            }
        }
        return null;
    }

    static List<Long> extractEffectNodeLongList(JsonNode effectNode, String fieldName) {
        if (effectNode == null || effectNode.isNull() || !StringUtils.hasText(fieldName)) {
            return List.of();
        }
        JsonNode value = effectNode.get(fieldName);
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : value) {
            if (node == null || node.isNull()) {
                continue;
            }
            Long id = node.isNumber() ? node.longValue() : asLong(node.asText());
            if (id == null || id <= 0 || ids.contains(id)) {
                continue;
            }
            ids.add(id);
        }
        return ids;
    }

    static List<Long> toLongList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            Long id = asLong(item);
            if (id == null || id <= 0 || ids.contains(id)) {
                continue;
            }
            ids.add(id);
        }
        return ids;
    }

    static List<String> toTextList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Object item : list) {
            String text = asText(item);
            if (!StringUtils.hasText(text) || texts.contains(text)) {
                continue;
            }
            texts.add(text);
        }
        return texts;
    }
}
