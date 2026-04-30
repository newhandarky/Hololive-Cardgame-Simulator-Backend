package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.Map;

class TriggerEffectConfirmPayloadBuilder {

    private static final String INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM = "TRIGGER_EFFECT_CONFIRM";

    Map<String, Object> buildBasePayload(
        Long decisionId,
        String sourceActionType,
        boolean confirmed
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", decisionId);
        payload.put("interactionType", INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
        payload.put("sourceActionType", sourceActionType);
        payload.put("confirmed", confirmed);
        return payload;
    }
}
