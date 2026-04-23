package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

final class MatchGiftExecutionHelper {

    private MatchGiftExecutionHelper() {
    }

    static boolean hasMeaningfulSequentialCost(List<String> costEffectTypes) {
        if (costEffectTypes == null || costEffectTypes.isEmpty()) {
            return false;
        }
        for (String effectType : costEffectTypes) {
            if (StringUtils.hasText(effectType) && !"UNIMPLEMENTED".equals(MatchEffectValueHelper.normalize(effectType))) {
                return true;
            }
        }
        return false;
    }

    static boolean areSequentialCostEffectsSatisfied(List<Map<String, Object>> costExecutions) {
        if (costExecutions == null || costExecutions.isEmpty()) {
            return false;
        }
        for (Map<String, Object> summary : costExecutions) {
            if (!isEffectSummaryApplied(summary)) {
                return false;
            }
        }
        return true;
    }

    static boolean isEffectSummaryApplied(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return false;
        }
        Object applied = summary.get("applied");
        if (applied instanceof Boolean appliedFlag) {
            return appliedFlag;
        }
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().endsWith("Applied")) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Number number && number.intValue() > 0) {
                return true;
            }
        }
        Object moved = summary.get("moved");
        return moved instanceof Boolean movedFlag && movedFlag;
    }

    static List<String> mergeEffectTypes(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return new ArrayList<>(merged);
    }
}
