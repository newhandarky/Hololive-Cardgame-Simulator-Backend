package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.Map;

class SendCheerInteractionPayloadBuilder {

    private static final String INTERACTION_TYPE_SEND_CHEER = "SEND_CHEER";

    Map<String, Object> buildInteractionConfirmedPayload(
        Long decisionId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        Long targetHolomemCardInstanceId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", decisionId);
        payload.put("interactionType", INTERACTION_TYPE_SEND_CHEER);
        payload.put("sourceActionType", sourceActionType);
        payload.put("sourceCardInstanceId", sourceCardInstanceId);
        payload.put("sourceCardId", sourceCardId);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        return payload;
    }

    Map<String, Object> buildTurnCheerActionPayload(
        Long sourceCardInstanceId,
        String sourceCardId,
        Long targetHolomemCardInstanceId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceCardInstanceId", sourceCardInstanceId);
        payload.put("sourceCardId", sourceCardId);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        return payload;
    }
}
