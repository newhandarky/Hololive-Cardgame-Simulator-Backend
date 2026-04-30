package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SupportOshiEffectPayloadBuilder {

    private static final String ACTION_TYPE_USE_OSHI_SKILL = "USE_OSHI_SKILL";

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
}
