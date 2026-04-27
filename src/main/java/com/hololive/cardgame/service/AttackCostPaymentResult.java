package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AttackCostPaymentResult(
    Map<String, Integer> baseCost,
    Map<String, Integer> reduction,
    Map<String, Integer> required,
    int requiredTotal,
    Map<String, Integer> paid,
    int paidTotal,
    List<String> paidCheerCardIds,
    List<Long> paidCheerCardInstanceIds,
    List<String> paidColors,
    boolean consumed
) {

    public AttackCostPaymentResult {
        baseCost = copy(baseCost);
        reduction = copy(reduction);
        required = copy(required);
        paid = copy(paid);
        paidCheerCardIds = paidCheerCardIds == null ? List.of() : List.copyOf(paidCheerCardIds);
        paidCheerCardInstanceIds = paidCheerCardInstanceIds == null ? List.of() : List.copyOf(paidCheerCardInstanceIds);
        paidColors = paidColors == null ? List.of() : List.copyOf(paidColors);
    }

    public Map<String, Object> toPaymentSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("required", required);
        summary.put("requiredTotal", requiredTotal);
        summary.put("paid", paid);
        summary.put("paidTotal", paidTotal);
        summary.put("paidCheerCardIds", paidCheerCardIds);
        summary.put("paidCheerCardInstanceIds", paidCheerCardInstanceIds);
        summary.put("paidColors", paidColors);
        summary.put("consumed", consumed);
        return Collections.unmodifiableMap(summary);
    }

    private static Map<String, Integer> copy(Map<String, Integer> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
