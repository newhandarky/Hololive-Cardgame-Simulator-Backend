package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AttackDownService {

    private final MatchGiftTriggerService matchGiftTriggerService;
    private final MatchTriggeredCombatEffectService matchTriggeredCombatEffectService;

    public AttackDownService(
        MatchGiftTriggerService matchGiftTriggerService,
        MatchTriggeredCombatEffectService matchTriggeredCombatEffectService
    ) {
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.matchTriggeredCombatEffectService = matchTriggeredCombatEffectService;
    }

    public AttackDownResult resolveDown(AttackDownContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack down 缺少必要上下文");
        }

        List<Map<String, Object>> additionalEffects = mergeEffectLists(
            context.officialCardArtExtraEffects(),
            context.officialOshiArtReactiveEffects()
        );
        Map<String, Object> attackSummaryForTriggeredChecks = mergeEffectSummaryForChecks(
            context.artSummary(),
            additionalEffects
        );
        boolean hasDownedHolomem = context.hasOpponentHolomem()
            && hasHolomemDowned(attackSummaryForTriggeredChecks);

        List<Map<String, Object>> giftTriggeredEffects = new ArrayList<>(
            matchGiftTriggerService.previewGiftTriggeredEffectsOnArt(
                context.matchId(),
                context.attackerUserId(),
                context.attackerCardInstanceId(),
                context.effectiveTargetCardInstanceId(),
                context.turnNumber(),
                context.artName()
            )
        );
        if (hasDownedHolomem) {
            giftTriggeredEffects.addAll(
                matchGiftTriggerService.previewGiftTriggeredEffectsOnDownedOpponent(
                    context.matchId(),
                    context.attackerUserId(),
                    context.attackerCardInstanceId(),
                    context.effectiveTargetCardInstanceId(),
                    context.turnNumber()
                )
            );
        }

        Map<String, Object> artDownTriggeredEffectSummary = hasDownedHolomem
            ? matchTriggeredCombatEffectService.applyArtDownTriggeredEffects(
                context.matchId(),
                context.attackerUserId(),
                context.attackerCardInstanceId(),
                context.artEffectJsonText()
            )
            : noOpArtDownTriggeredEffectSummary();

        return new AttackDownResult(
            attackSummaryForTriggeredChecks,
            hasDownedHolomem,
            giftTriggeredEffects,
            artDownTriggeredEffectSummary,
            extractDownEventPreview(context.artSummary())
        );
    }

    private List<Map<String, Object>> mergeEffectLists(
        List<Map<String, Object>> left,
        List<Map<String, Object>> right
    ) {
        if ((left == null || left.isEmpty()) && (right == null || right.isEmpty())) {
            return List.of();
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return merged;
    }

    private Map<String, Object> mergeEffectSummaryForChecks(
        Map<String, Object> primary,
        List<Map<String, Object>> additionalEffects
    ) {
        if ((additionalEffects == null || additionalEffects.isEmpty()) && primary != null) {
            return primary;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        List<Object> executed = new ArrayList<>();
        if (primary != null) {
            executed.add(primary);
        }
        if (additionalEffects != null) {
            executed.addAll(additionalEffects);
        }
        merged.put("executedEffects", executed);
        return merged;
    }

    private boolean hasHolomemDowned(Object summaryObject) {
        if (summaryObject == null) {
            return false;
        }
        if (summaryObject instanceof Map<?, ?> map) {
            Object downed = map.get("downed");
            if (toBoolean(downed)) {
                return true;
            }
            Object executedEffects = map.get("executedEffects");
            if (executedEffects instanceof List<?> effects) {
                for (Object effect : effects) {
                    if (hasHolomemDowned(effect)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Map<String, Object> extractDownEventPreview(Map<String, Object> artSummary) {
        if (artSummary == null || artSummary.isEmpty()) {
            return null;
        }
        Object downEvent = artSummary.get("downEvent");
        if (downEvent instanceof Map<?, ?> map) {
            Map<String, Object> preview = castToMap(map);
            if (toBoolean(preview.get("triggered")) && toBoolean(preview.get("deferred"))) {
                return preview;
            }
        }
        Object executedEffects = artSummary.get("executedEffects");
        if (!(executedEffects instanceof List<?> list)) {
            return null;
        }
        for (Object effect : list) {
            if (!(effect instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> nested = extractDownEventPreview(castToMap(map));
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private Map<String, Object> noOpArtDownTriggeredEffectSummary() {
        return Map.of(
            "triggerType", "ART_DOWNED_OPPONENT",
            "requestedEffects", List.of(),
            "executedEffects", List.of(),
            "unsupportedEffects", List.of(),
            "skippedEffects", List.of(),
            "applied", false
        );
    }

    private Map<String, Object> castToMap(Map<?, ?> map) {
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            typed.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return typed;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }
}
