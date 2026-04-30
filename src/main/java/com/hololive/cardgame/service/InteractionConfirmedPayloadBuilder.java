package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class InteractionConfirmedPayloadBuilder {

    Map<String, Object> buildLookTopDeckPayload(
        Long decisionId,
        String decisionType,
        String sourceActionType,
        Long lookedCardInstanceId,
        boolean keepOnTop
    ) {
        Map<String, Object> payload = buildBasePayload(decisionId, decisionType, sourceActionType);
        payload.put("lookedCardInstanceId", lookedCardInstanceId);
        payload.put("placement", keepOnTop ? "TOP" : "BOTTOM");
        return payload;
    }

    Map<String, Object> buildLookZonePayload(
        Long decisionId,
        String decisionType,
        String sourceActionType,
        int lookedCardCount
    ) {
        Map<String, Object> payload = buildBasePayload(decisionId, decisionType, sourceActionType);
        payload.put("lookedCardCount", lookedCardCount);
        return payload;
    }

    Map<String, Object> buildReorderDeckBottomPayload(
        Long decisionId,
        String decisionType,
        String sourceActionType,
        List<Long> orderedCardInstanceIds
    ) {
        Map<String, Object> payload = buildBasePayload(decisionId, decisionType, sourceActionType);
        payload.put("orderedCardInstanceIds", orderedCardInstanceIds);
        return payload;
    }

    private Map<String, Object> buildBasePayload(
        Long decisionId,
        String decisionType,
        String sourceActionType
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", decisionId);
        payload.put("decisionType", decisionType);
        payload.put("sourceActionType", sourceActionType);
        return payload;
    }
}
