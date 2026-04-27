package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record BloomResolutionResult(
    MatchEntity match,
    Long actingUserId,
    int turnNumber,
    Long sourceCardInstanceId,
    String sourceCardId,
    String sourceLevelType,
    Long targetHolomemId,
    Long targetHolomemCardInstanceId,
    String targetPreviousCardId,
    String targetPreviousLevelType,
    String targetZone,
    int damageCarried,
    int stackDepth,
    boolean bloomLevelOverrideApplied,
    Long consumedExtraBloomAllowanceId,
    Map<String, Object> passiveGiftSummary,
    Map<String, Object> bloomEffectSummary,
    Map<String, Object> triggerSummary,
    Long followupInteractionId
) {

    public BloomResolutionResult {
        passiveGiftSummary = copy(passiveGiftSummary);
        bloomEffectSummary = copy(bloomEffectSummary);
        triggerSummary = copy(triggerSummary);
    }

    public Map<String, Object> actionPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        payload.put("fromCardId", targetPreviousCardId);
        payload.put("fromLevel", targetPreviousLevelType);
        payload.put("toCardInstanceId", sourceCardInstanceId);
        payload.put("toCardId", sourceCardId);
        payload.put("toLevel", sourceLevelType);
        payload.put("damageCarried", damageCarried);
        payload.put("stackDepth", stackDepth);
        payload.put("bloomLevelOverrideApplied", bloomLevelOverrideApplied);
        payload.put("passiveGiftSummary", passiveGiftSummary);
        payload.put("bloomEffect", bloomEffectSummary);
        payload.put("triggerSummary", triggerSummary);
        if (followupInteractionId != null) {
            payload.put("followupInteractionId", followupInteractionId);
        }
        return payload;
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
