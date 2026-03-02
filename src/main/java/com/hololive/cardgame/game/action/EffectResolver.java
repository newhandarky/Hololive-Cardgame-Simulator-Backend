package com.hololive.cardgame.game.action;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

@Component
public class EffectResolver {

    private static final Set<String> FIRST_BATCH_EFFECT_TYPES = Set.of(
        "DAMAGE",
        "BUFF",
        "DEBUFF",
        "SEARCH",
        "MOVE_ZONE",
        "ADD_CHEER",
        "REMOVE_CHEER",
        "DRAW",
        "HEAL",
        "ROLL_DICE",
        "DOWN_EXTRA_LIFE"
    );

    private final Map<String, BiFunction<EffectContext, JsonNode, List<AtomicAction>>> resolvers = createResolvers();

    public List<AtomicAction> resolve(EffectContext context, String effectType, JsonNode effectJson) {
        String normalizedEffectType = normalize(effectType);
        BiFunction<EffectContext, JsonNode, List<AtomicAction>> resolver = resolvers.get(normalizedEffectType);
        if (resolver != null) {
            return resolver.apply(context, effectJson);
        }
        return List.of();
    }

    public boolean hasResolver(String effectType) {
        return resolvers.containsKey(normalize(effectType));
    }

    public Set<String> mappedEffectTypes() {
        return Set.copyOf(resolvers.keySet());
    }

    private Map<String, BiFunction<EffectContext, JsonNode, List<AtomicAction>>> createResolvers() {
        Map<String, BiFunction<EffectContext, JsonNode, List<AtomicAction>>> map = new LinkedHashMap<>();
        map.put("DRAW", this::resolveDraw);
        map.put("MOVE_ZONE", this::resolveMoveZone);
        map.put("DAMAGE", this::resolveDamage);
        map.put("ADD_CHEER", this::resolveAddCheer);
        map.put("SEND_CHEER", this::resolveAddCheer);
        map.put("REDUCE_LIFE", this::resolveReduceLife);
        map.put("DOWN_EXTRA_LIFE", this::resolveDownExtraLife);
        map.put("BUFF", this::resolveBuffDebuff);
        map.put("DEBUFF", this::resolveBuffDebuff);
        map.put("SEARCH", this::resolveSearch);
        map.put("REMOVE_CHEER", this::resolveRemoveCheer);
        map.put("HEAL", this::resolveHeal);
        map.put("ROLL_DICE", this::resolveRollDice);
        return Map.copyOf(map);
    }

    private List<AtomicAction> resolveDraw(EffectContext context, JsonNode effectJson) {
        int drawCount = readInt(effectJson, "drawCount", 1);
        return List.of(new DrawAction(context.actorUserId(), Math.max(drawCount, 0), "DECK", "HAND"));
    }

    private List<AtomicAction> resolveMoveZone(EffectContext context, JsonNode effectJson) {
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
            return List.of(unimplemented("MOVE_ZONE", "MISSING_REQUIRED_FIELDS"));
        }
        return List.of(new MoveZoneAction(cardInstanceId, context.actorUserId(), fromZone, toZone, orderIndex, faceDown));
    }

    private List<AtomicAction> resolveDamage(EffectContext context, JsonNode effectJson) {
        Long targetHolomemId = readLong(effectJson, "targetHolomemId");
        int amount = readInt(effectJson, "amount", 0);
        if (targetHolomemId == null || targetHolomemId <= 0 || amount <= 0) {
            return List.of(unimplemented("DAMAGE", "MISSING_TARGET_OR_AMOUNT"));
        }
        return List.of(new DamageAction(targetHolomemId, amount, context.sourceActionType()));
    }

    private List<AtomicAction> resolveAddCheer(EffectContext context, JsonNode effectJson) {
        Long cheerCardInstanceId = readLong(effectJson, "cheerCardInstanceId");
        Long targetHolomemId = readLong(effectJson, "targetHolomemId");
        if (cheerCardInstanceId == null || cheerCardInstanceId <= 0 || targetHolomemId == null || targetHolomemId <= 0) {
            return List.of(unimplemented("ADD_CHEER", "MISSING_CHEER_OR_TARGET"));
        }
        return List.of(new SendCheerAction(cheerCardInstanceId, targetHolomemId, context.sourceActionType()));
    }

    private List<AtomicAction> resolveReduceLife(EffectContext context, JsonNode effectJson) {
        Long targetUserId = readLong(effectJson, "targetUserId");
        int amount = readInt(effectJson, "amount", 1);
        Long resolvedTargetUserId = targetUserId == null || targetUserId <= 0 ? context.actorUserId() : targetUserId;
        if (resolvedTargetUserId == null || resolvedTargetUserId <= 0 || amount <= 0) {
            return List.of(unimplemented("REDUCE_LIFE", "MISSING_TARGET_OR_AMOUNT"));
        }
        return List.of(new ReduceLifeAction(resolvedTargetUserId, amount, context.sourceActionType()));
    }

    private List<AtomicAction> resolveDownExtraLife(EffectContext context, JsonNode effectJson) {
        Long targetUserId = readLong(effectJson, "targetUserId");
        int amount = readInt(effectJson, "amount", 1);
        Long resolvedTargetUserId = targetUserId == null || targetUserId <= 0 ? context.actorUserId() : targetUserId;
        if (resolvedTargetUserId == null || resolvedTargetUserId <= 0 || amount <= 0) {
            return List.of(unimplemented("DOWN_EXTRA_LIFE", "MISSING_TARGET_OR_AMOUNT"));
        }
        return List.of(new ReduceLifeAction(resolvedTargetUserId, amount, "DOWN_EXTRA_LIFE"));
    }

    private List<AtomicAction> resolveBuffDebuff(EffectContext context, JsonNode effectJson) {
        return List.of(unimplemented(readText(effectJson, "type"), "HANDLED_BY_LEGACY_EFFECT_FLOW"));
    }

    private List<AtomicAction> resolveSearch(EffectContext context, JsonNode effectJson) {
        return List.of(unimplemented("SEARCH", "REQUIRES_INTERACTIVE_SELECTION"));
    }

    private List<AtomicAction> resolveRemoveCheer(EffectContext context, JsonNode effectJson) {
        return List.of(unimplemented("REMOVE_CHEER", "REQUIRES_TARGET_AND_DESTINATION_RULES"));
    }

    private List<AtomicAction> resolveHeal(EffectContext context, JsonNode effectJson) {
        return List.of(unimplemented("HEAL", "LEGACY_HEAL_PATH_NOT_SPLIT_YET"));
    }

    private List<AtomicAction> resolveRollDice(EffectContext context, JsonNode effectJson) {
        return List.of(unimplemented("ROLL_DICE", "REQUIRES_DICE_AND_BRANCHING_FLOW"));
    }

    private UnimplementedAction unimplemented(String effectType, String reason) {
        String normalized = normalize(effectType);
        if (!hasText(normalized)) {
            normalized = "UNIMPLEMENTED";
        }
        return new UnimplementedAction(normalized, reason);
    }

    public Set<String> firstBatchEffectTypes() {
        return FIRST_BATCH_EFFECT_TYPES;
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
