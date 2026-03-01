package com.hololive.cardgame.game.action;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class EffectResolver {

    public List<AtomicAction> resolve(EffectContext context, String effectType, JsonNode effectJson) {
        String normalizedEffectType = normalize(effectType);
        if ("DRAW".equals(normalizedEffectType)) {
            int drawCount = readInt(effectJson, "drawCount", 1);
            return List.of(new DrawAction(context.actorUserId(), Math.max(drawCount, 0), "DECK", "HAND"));
        }
        if ("MOVE_ZONE".equals(normalizedEffectType)) {
            Long cardInstanceId = readLong(effectJson, "cardInstanceId");
            String fromZone = readText(effectJson, "fromZone");
            String toZone = readText(effectJson, "toZone");
            Integer orderIndex = effectJson != null && effectJson.hasNonNull("orderIndex")
                ? effectJson.get("orderIndex").asInt()
                : null;
            Boolean faceDown = effectJson != null && effectJson.hasNonNull("faceDown")
                ? effectJson.get("faceDown").asBoolean()
                : null;
            if (cardInstanceId == null || cardInstanceId <= 0 || !hasText(fromZone) || !hasText(toZone)) {
                return List.of();
            }
            return List.of(new MoveZoneAction(cardInstanceId, context.actorUserId(), fromZone, toZone, orderIndex, faceDown));
        }
        if ("DAMAGE".equals(normalizedEffectType)) {
            Long targetHolomemId = readLong(effectJson, "targetHolomemId");
            int amount = readInt(effectJson, "amount", 0);
            if (targetHolomemId == null || targetHolomemId <= 0 || amount <= 0) {
                return List.of();
            }
            return List.of(new DamageAction(targetHolomemId, amount, context.sourceActionType()));
        }
        if ("REDUCE_LIFE".equals(normalizedEffectType)) {
            Long targetUserId = readLong(effectJson, "targetUserId");
            int amount = readInt(effectJson, "amount", 1);
            Long resolvedTargetUserId = targetUserId == null || targetUserId <= 0 ? context.actorUserId() : targetUserId;
            if (resolvedTargetUserId == null || resolvedTargetUserId <= 0 || amount <= 0) {
                return List.of();
            }
            return List.of(new ReduceLifeAction(resolvedTargetUserId, amount, context.sourceActionType()));
        }
        if ("ADD_CHEER".equals(normalizedEffectType) || "SEND_CHEER".equals(normalizedEffectType)) {
            Long cheerCardInstanceId = readLong(effectJson, "cheerCardInstanceId");
            Long targetHolomemId = readLong(effectJson, "targetHolomemId");
            if (cheerCardInstanceId == null || cheerCardInstanceId <= 0 || targetHolomemId == null || targetHolomemId <= 0) {
                return List.of();
            }
            return List.of(new SendCheerAction(cheerCardInstanceId, targetHolomemId, context.sourceActionType()));
        }
        if ("DOWN_EXTRA_LIFE".equals(normalizedEffectType)) {
            int amount = readInt(effectJson, "amount", 1);
            Long targetUserId = readLong(effectJson, "targetUserId");
            Long resolvedTargetUserId = targetUserId == null || targetUserId <= 0 ? context.actorUserId() : targetUserId;
            if (resolvedTargetUserId == null || resolvedTargetUserId <= 0 || amount <= 0) {
                return List.of();
            }
            return List.of(new ReduceLifeAction(resolvedTargetUserId, amount, "DOWN_EXTRA_LIFE"));
        }
        if ("BUFF".equals(normalizedEffectType) || "DEBUFF".equals(normalizedEffectType)) {
            // 由 match_turn_effects 寫入流程處理，暫不轉為 AtomicAction。
            return List.of();
        }
        if ("SEARCH".equals(normalizedEffectType)) {
            // 需互動選牌，暫由既有 pending decision 流程處理。
            return List.of();
        }
        if ("REMOVE_CHEER".equals(normalizedEffectType)) {
            // 需指定來源與退回區域，暫由既有邏輯處理。
            return List.of();
        }
        if ("HEAL".equals(normalizedEffectType)) {
            // 目前治療流程依賴既有資料結構，後續再拆成原子動作。
            return List.of();
        }
        if ("ROLL_DICE".equals(normalizedEffectType)) {
            // 隨機來源與條件分支仍由既有 effect service 統一處理。
            return List.of();
        }
        // P1-1: 先建立對映骨架，其餘 effectType 在 P1-2/P1-3 持續補齊。
        return List.of();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int readInt(JsonNode node, String field, int fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value.isInt() || value.isLong() ? value.asInt() : fallback;
    }

    private Long readLong(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isLong() || value.isInt()) {
            return value.asLong();
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

    private String readText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isTextual() ? value.asText() : null;
    }
}
