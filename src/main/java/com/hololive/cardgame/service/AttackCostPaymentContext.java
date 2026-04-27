package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttackCostPaymentContext(
    Long matchId,
    Long ownerUserId,
    Long attackerHolomemId,
    Map<String, Integer> baseCost,
    Map<String, Integer> costReduction,
    boolean consume
) {

    public AttackCostPaymentContext {
        baseCost = copy(baseCost);
        costReduction = copy(costReduction);
    }

    public static AttackCostPaymentContext preview(
        Long matchId,
        Long ownerUserId,
        Long attackerHolomemId,
        Map<String, Integer> baseCost,
        Map<String, Integer> costReduction
    ) {
        return new AttackCostPaymentContext(matchId, ownerUserId, attackerHolomemId, baseCost, costReduction, false);
    }

    private static Map<String, Integer> copy(Map<String, Integer> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
