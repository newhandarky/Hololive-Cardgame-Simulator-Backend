package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CardEffectValidator {

    private static final String EFFECT_SCHEMA_PATH = "effects/effect-schema.json";

    private final ObjectMapper objectMapper;
    private final Set<String> allowedEffectTypes;
    private final String placeholderType;
    private final Set<String> placeholderRequiredAny;

    public CardEffectValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        EffectSchemaConfig schema = loadSchemaConfig();
        this.allowedEffectTypes = schema.allowedEffectTypes();
        this.placeholderType = schema.placeholderType();
        this.placeholderRequiredAny = schema.placeholderRequiredAny();
    }

    /**
     * 驗證「任意 JSON 物件」格式（例如 passiveEffectJson、conditionJson）。
     */
    public void validateJsonObject(String rawJson, String fieldName) {
        parseObject(rawJson, fieldName);
    }

    /**
     * 驗證 effect_json（至少要有 type，且 type 必須在白名單內）。
     */
    public void validateEffectJson(String rawJson, String fieldName) {
        JsonNode node = parseObject(rawJson, fieldName);
        JsonNode typeNode = node.get("type");
        if (typeNode == null || !typeNode.isTextual() || !StringUtils.hasText(typeNode.asText())) {
            throw new IllegalArgumentException(fieldName + " 缺少 type（字串）");
        }

        String effectType = normalizeEffectType(typeNode.asText());
        if (!allowedEffectTypes.contains(effectType)) {
            throw new IllegalArgumentException(fieldName + " type 不支援：" + effectType);
        }

        if (placeholderType.equals(effectType)) {
            boolean hasAnyRaw = placeholderRequiredAny.stream()
                .map(node::get)
                .filter(Objects::nonNull)
                .anyMatch(JsonNode::isTextual);
            if (!hasAnyRaw) {
                throw new IllegalArgumentException(
                    fieldName + " 使用 " + placeholderType + " 時，至少要提供 "
                        + String.join(" 或 ", placeholderRequiredAny)
                );
            }
        }
    }

    private JsonNode parseObject(String rawJson, String fieldName) {
        if (!StringUtils.hasText(rawJson)) {
            throw new IllegalArgumentException(fieldName + " 不可為空");
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            if (!node.isObject()) {
                throw new IllegalArgumentException(fieldName + " 必須為 JSON 物件");
            }
            return node;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(fieldName + " 不是合法 JSON：" + ex.getOriginalMessage(), ex);
        }
    }

    private EffectSchemaConfig loadSchemaConfig() {
        try (InputStream inputStream = Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(EFFECT_SCHEMA_PATH)) {
            if (inputStream == null) {
                throw new IllegalStateException("找不到 effect schema：" + EFFECT_SCHEMA_PATH);
            }

            JsonNode root = objectMapper.readTree(inputStream);
            Set<String> types = new HashSet<>();
            JsonNode typeArray = root.get("allowedEffectTypes");
            if (typeArray != null && typeArray.isArray()) {
                for (JsonNode item : typeArray) {
                    if (item.isTextual() && StringUtils.hasText(item.asText())) {
                        types.add(normalizeEffectType(item.asText()));
                    }
                }
            }
            if (types.isEmpty()) {
                throw new IllegalStateException("effect schema 缺少 allowedEffectTypes");
            }

            String loadedPlaceholderType = normalizeEffectType(root.path("placeholderType").asText());
            if (!StringUtils.hasText(loadedPlaceholderType)) {
                loadedPlaceholderType = "UNIMPLEMENTED";
            }

            Set<String> requiredAny = new HashSet<>();
            JsonNode requiredArray = root.get("placeholderRequiredAny");
            if (requiredArray != null && requiredArray.isArray()) {
                Iterator<JsonNode> iterator = requiredArray.elements();
                while (iterator.hasNext()) {
                    JsonNode field = iterator.next();
                    if (field.isTextual() && StringUtils.hasText(field.asText())) {
                        requiredAny.add(field.asText());
                    }
                }
            }
            if (requiredAny.isEmpty()) {
                requiredAny.add("rawText");
            }

            return new EffectSchemaConfig(types, loadedPlaceholderType, requiredAny);
        } catch (IOException ex) {
            throw new IllegalStateException("讀取 effect schema 失敗", ex);
        }
    }

    private String normalizeEffectType(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private record EffectSchemaConfig(
        Set<String> allowedEffectTypes,
        String placeholderType,
        Set<String> placeholderRequiredAny
    ) {}
}
