package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AttackRestAndPayloadService {

    public AttackRestAndPayloadResult resolve(AttackRestAndPayloadContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack rest and payload 缺少必要上下文");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attackerCardInstanceId", context.attackerCardInstanceId());
        payload.put("attackerCardId", context.attackerCardId());
        payload.put("attackerZone", context.attackerZone());
        payload.put("targetCardInstanceId", context.targetCardInstanceId());
        payload.put("passiveGiftTargetRestrictionToCollab", context.passiveGiftTargetRestrictionToCollab());
        payload.put("passiveGiftTargetRestrictionApplied", context.passiveGiftTargetRestrictionApplied());
        payload.put("damageRedirectApplied", context.damageRedirectApplied());
        payload.put("targetMainColor", context.targetMainColor());
        payload.put("artName", context.artName());
        payload.put("artOrderIndex", context.artOrderIndex());
        payload.put("artBaseCost", context.artBaseCost());
        payload.put("artCost", context.artCost());
        payload.put("passiveGiftArtCostReduction", context.passiveGiftArtCostReduction());
        payload.put("costPayment", context.costPayment());
        payload.putAll(safeMap(context.damagePayloadFields()));
        putIfNotEmpty(payload, "holoxReveal", context.holoxReveal());
        putIfNotEmpty(payload, "hbp02039SupportRecovery", context.hbp02039SupportRecovery());
        putIfNotEmpty(payload, "hbp02040LifeLoss", context.hbp02040LifeLoss());
        payload.put("defenderDamageReceivedGift", context.defenderDamageReceivedGift());
        payload.put("artTotalDamage", context.artTotalDamage());
        payload.put("effect", context.artSummary());
        putIfNotEmpty(payload, "officialCardArtExtra", context.officialCardArtExtraSummary());
        putIfNotEmpty(payload, "officialOshiArtReactive", context.officialOshiArtReactiveSummary());
        putIfNotEmpty(payload, "officialOshiSelfDowned", context.officialOshiSelfDownedSummary());
        payload.put("artDownTriggeredEffects", context.artDownTriggeredEffects());
        payload.put("postTriggerEffects", context.postTriggerEffects());
        payload.put("defenderGiftEffects", context.defenderGiftEffects());
        payload.put("hasNextPerformanceAction", context.hasNextPerformanceAction());
        payload.put("lostLifeCardInstanceId", context.lostLifeCardInstanceId());
        putPendingDecisionPayload(payload, context.postTriggerConfirmDecision());
        putDefenderPendingDecisionPayload(payload, context.defenderGiftConfirmDecision());

        return new AttackRestAndPayloadResult(
            payload,
            mergeEffectSummaryForChecks(context.artSummary(), context.additionalEffectSummaries())
        );
    }

    private void putPendingDecisionPayload(Map<String, Object> payload, AttackPendingDecision decision) {
        if (decision == null || !decision.hasDecision()) {
            return;
        }
        payload.put("pendingInteractionDecisionId", decision.decisionId());
        payload.put("pendingInteractionDecisionType", decision.decisionType());
    }

    private void putDefenderPendingDecisionPayload(Map<String, Object> payload, AttackPendingDecision decision) {
        if (decision == null || !decision.hasDecision()) {
            return;
        }
        payload.put("defenderPendingInteractionDecisionId", decision.decisionId());
        payload.put("defenderPendingInteractionDecisionType", decision.decisionType());
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

    private Map<String, Object> safeMap(Map<String, Object> source) {
        return source == null ? Map.of() : source;
    }

    private void putIfNotEmpty(Map<String, Object> payload, String key, Map<String, Object> value) {
        if (value != null && !value.isEmpty()) {
            payload.put(key, value);
        }
    }
}
