package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SupportOshiEffectPayloadBuilder {

    private static final String ACTION_TYPE_USE_OSHI_SKILL = "USE_OSHI_SKILL";
    private static final String SUPPORT_DECISION_TYPE_CARD_SELECTION = "CARD_SELECTION";

    Map<String, Object> buildSupportSelectionPendingPayload(
        Long decisionId,
        Long cardInstanceId,
        String cardId,
        MatchEffectService.SupportDecisionPlan decisionPlan
    ) {
        Map<String, Object> payload = buildSelectionPendingBasePayload(decisionId, decisionPlan);
        payload.put("cardInstanceId", cardInstanceId);
        payload.put("cardId", cardId);
        return payload;
    }

    Map<String, Object> buildOshiSkillSelectionPendingPayload(
        Long decisionId,
        String skillType,
        String skillName,
        Long oshiCardInstanceId,
        String oshiCardId,
        int holopowerCost,
        Map<String, Object> holopowerPayment,
        MatchEffectService.SupportDecisionPlan decisionPlan
    ) {
        Map<String, Object> payload = buildSelectionPendingBasePayload(decisionId, decisionPlan);
        payload.put("skillType", skillType);
        payload.put("skillName", skillName);
        payload.put("oshiCardInstanceId", oshiCardInstanceId);
        payload.put("oshiCardId", oshiCardId);
        payload.put("holopowerCost", holopowerCost);
        payload.put("holopowerPayment", holopowerPayment);
        return payload;
    }

    Map<String, Object> buildPlaySupportEffectPayload(
        Long cardInstanceId,
        String cardId,
        boolean limited,
        Long targetHolomemCardInstanceId,
        List<Long> selectedCardInstanceIds,
        Map<String, Object> effectSummary
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", cardInstanceId);
        payload.put("cardId", cardId);
        payload.put("limited", limited);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        payload.put("effect", effectSummary);
        return payload;
    }

    Map<String, Object> buildResolvedSelectionEffectPayload(
        Long decisionId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        boolean limited,
        Long targetHolomemCardInstanceId,
        List<Long> selectedCardInstanceIds,
        Map<String, Object> effectSummary
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", decisionId);
        payload.put("sourceActionType", sourceActionType);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        payload.put("effect", effectSummary);
        if (ACTION_TYPE_USE_OSHI_SKILL.equals(sourceActionType)) {
            payload.put("oshiCardInstanceId", sourceCardInstanceId);
            payload.put("oshiCardId", sourceCardId);
        } else {
            payload.put("cardInstanceId", sourceCardInstanceId);
            payload.put("cardId", sourceCardId);
            payload.put("limited", limited);
        }
        return payload;
    }

    Map<String, Object> buildOshiSkillEffectPayload(
        String skillType,
        String skillName,
        Long oshiCardInstanceId,
        String oshiCardId,
        int holopowerCost,
        Map<String, Object> holopowerPayment,
        Long targetHolomemCardInstanceId,
        List<Long> selectedCardInstanceIds,
        Map<String, Object> effectSummary
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skillType", skillType);
        payload.put("skillName", skillName);
        payload.put("oshiCardInstanceId", oshiCardInstanceId);
        payload.put("oshiCardId", oshiCardId);
        payload.put("holopowerCost", holopowerCost);
        payload.put("holopowerPayment", holopowerPayment);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        payload.put("effect", effectSummary);
        return payload;
    }

    private Map<String, Object> buildSelectionPendingBasePayload(
        Long decisionId,
        MatchEffectService.SupportDecisionPlan decisionPlan
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", decisionId);
        payload.put("decisionType", SUPPORT_DECISION_TYPE_CARD_SELECTION);
        payload.put("effectType", decisionPlan.effectType());
        payload.put("candidateCount", decisionPlan.candidates().size());
        payload.put("minSelect", decisionPlan.minSelect());
        payload.put("maxSelect", decisionPlan.maxSelect());
        return payload;
    }
}
