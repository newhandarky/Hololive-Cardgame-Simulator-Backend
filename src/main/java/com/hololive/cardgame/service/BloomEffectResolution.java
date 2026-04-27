package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

public record BloomEffectResolution(
    Map<String, Object> passiveGiftSummary,
    Map<String, Object> bloomEffectSummary,
    Map<String, Object> triggerSummary,
    Long pendingInteractionDecisionId,
    String pendingInteractionDecisionType,
    List<Map<String, Object>> triggerResolutionOrder,
    boolean deferredEffect
) {
    public void appendFollowupPayload(Map<String, Object> payload) {
        if (payload == null || pendingInteractionDecisionId == null) {
            return;
        }
        payload.put("pendingInteractionDecisionId", pendingInteractionDecisionId);
        payload.put("pendingInteractionDecisionType", pendingInteractionDecisionType);
    }
}
