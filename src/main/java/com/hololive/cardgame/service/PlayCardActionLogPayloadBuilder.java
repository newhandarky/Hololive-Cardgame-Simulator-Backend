package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.Map;

class PlayCardActionLogPayloadBuilder {

    Map<String, Object> buildPayload(
        PlayCardAction action,
        PlayCardResolutionResult resolutionResult,
        PlayCardEffectResolution effectResolution
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", resolutionResult.cardInstanceId());
        payload.put("cardId", resolutionResult.cardId());
        payload.put("targetZone", resolutionResult.targetZone());
        payload.put("enteredTurn", resolutionResult.enteredTurnNumber());
        payload.put("faceDown", resolutionResult.faceDown());
        payload.put("idempotencyKey", action.idempotencyKey());
        payload.put("triggerSummary", effectResolution.triggerSummary());
        if (!resolutionResult.openingReset()) {
            payload.put("giftEffect", effectResolution.giftEffectSummary());
            payload.put("triggerResolutionOrder", effectResolution.triggerResolutionOrder());
            if (effectResolution.hasPendingInteraction()) {
                payload.put("pendingInteractionDecisionId", effectResolution.pendingInteractionDecisionId());
                payload.put("pendingInteractionDecisionType", effectResolution.pendingInteractionDecisionType());
            }
        }
        return payload;
    }

    String resolveLegacyActionType(PlayCardResolutionResult resolutionResult) {
        if (resolutionResult.openingReset()) {
            return "CENTER".equals(resolutionResult.targetZone()) ? "OPENING_SET_CENTER" : "OPENING_SET_BACK";
        }
        return "PLAY_TO_STAGE";
    }
}
