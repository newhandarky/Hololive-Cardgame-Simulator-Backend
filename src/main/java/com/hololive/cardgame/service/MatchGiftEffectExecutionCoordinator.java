package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftExecutionSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

final class MatchGiftEffectExecutionCoordinator {

    private final EffectTextParser effectTextParser;
    private final MatchEffectTypeInferenceService effectTypeInferenceService;

    MatchGiftEffectExecutionCoordinator(
        EffectTextParser effectTextParser,
        MatchEffectTypeInferenceService effectTypeInferenceService
    ) {
        this.effectTextParser = effectTextParser;
        this.effectTypeInferenceService = effectTypeInferenceService;
    }

    GiftExecutionSummary execute(
        String giftText,
        JsonNode giftNode,
        GiftEffectExecutor executor
    ) {
        int clauseSeparatorIndex = findClauseSeparator(giftText);
        List<String> costEffectTypes = clauseSeparatorIndex >= 0
            ? effectTypeInferenceService.inferEffectTypes(extractCostClause(giftText))
            : List.of();
        List<String> resolvedEffectTypes = clauseSeparatorIndex >= 0
            ? effectTypeInferenceService.inferEffectTypes(extractResolvedEffectClause(giftText))
            : List.of();
        boolean hasMeaningfulSequentialCost = MatchGiftExecutionHelper.hasMeaningfulSequentialCost(costEffectTypes);
        List<String> effectTypes;
        if (clauseSeparatorIndex >= 0 && hasMeaningfulSequentialCost) {
            effectTypes = MatchGiftExecutionHelper.mergeEffectTypes(costEffectTypes, resolvedEffectTypes);
        } else if (clauseSeparatorIndex >= 0 && !resolvedEffectTypes.isEmpty()) {
            effectTypes = resolvedEffectTypes;
        } else {
            effectTypes = effectTypeInferenceService.inferEffectTypes(giftText);
        }

        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();
        List<Map<String, Object>> costExecutions = new ArrayList<>();
        if (clauseSeparatorIndex >= 0 && hasMeaningfulSequentialCost) {
            for (String effectType : costEffectTypes) {
                executeSafely(giftNode, effectType, executor, executed, unsupported, skippedEffects, costExecutions);
            }
            if (!costEffectTypes.isEmpty() && !MatchGiftExecutionHelper.areSequentialCostEffectsSatisfied(costExecutions)) {
                for (String effectType : resolvedEffectTypes) {
                    Map<String, Object> skipped = buildSkippedEffect(effectType, "前置成本未支付");
                    executed.add(skipped);
                    skippedEffects.add(skipped);
                }
            } else {
                for (String effectType : resolvedEffectTypes) {
                    executeSafely(giftNode, effectType, executor, executed, unsupported, skippedEffects, null);
                }
            }
        } else {
            for (String effectType : effectTypes) {
                executeSafely(giftNode, effectType, executor, executed, unsupported, skippedEffects, null);
            }
        }
        return new GiftExecutionSummary(effectTypes, executed, unsupported, skippedEffects);
    }

    private void executeSafely(
        JsonNode giftNode,
        String effectType,
        GiftEffectExecutor executor,
        List<Map<String, Object>> executed,
        List<String> unsupported,
        List<Map<String, Object>> skippedEffects,
        List<Map<String, Object>> costExecutions
    ) {
        try {
            Map<String, Object> summary = executor.execute(effectType, giftNode);
            if (summary != null) {
                executed.add(summary);
                if (costExecutions != null) {
                    costExecutions.add(summary);
                }
            }
        } catch (UnsupportedOperationException ex) {
            unsupported.add(effectType);
            Map<String, Object> skipped = buildSkippedEffect(effectType, "UNSUPPORTED_EFFECT");
            executed.add(skipped);
            skippedEffects.add(skipped);
            if (costExecutions != null) {
                costExecutions.add(skipped);
            }
        } catch (RuntimeException ex) {
            Map<String, Object> skipped = buildSkippedEffect(effectType, ex.getMessage());
            executed.add(skipped);
            skippedEffects.add(skipped);
            if (costExecutions != null) {
                costExecutions.add(skipped);
            }
        }
    }

    private Map<String, Object> buildSkippedEffect(String effectType, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectTextParser.normalizeEffectType(effectType));
        summary.put("applied", false);
        summary.put("skipped", true);
        summary.put("reason", StringUtils.hasText(reason) ? reason : "EFFECT_SKIPPED");
        return summary;
    }

    private String extractCostClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int splitIndex = findClauseSeparator(rawText);
        return splitIndex < 0 ? rawText : rawText.substring(0, splitIndex).trim();
    }

    private String extractResolvedEffectClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int splitIndex = findClauseSeparator(rawText);
        return splitIndex < 0 || splitIndex + 1 >= rawText.length() ? rawText : rawText.substring(splitIndex + 1).trim();
    }

    private int findClauseSeparator(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return -1;
        }
        int fullWidthIndex = rawText.indexOf('：');
        int halfWidthIndex = rawText.indexOf(':');
        if (fullWidthIndex < 0) {
            return halfWidthIndex;
        }
        if (halfWidthIndex < 0) {
            return fullWidthIndex;
        }
        return Math.min(fullWidthIndex, halfWidthIndex);
    }

    @FunctionalInterface
    interface GiftEffectExecutor {
        Map<String, Object> execute(String effectType, JsonNode giftNode);
    }
}
