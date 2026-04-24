package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MatchEffectDamageService {

    private final ObjectMapper objectMapper;
    private final MatchEffectService matchEffectService;

    public MatchEffectDamageService(
        ObjectMapper objectMapper,
        MatchEffectService matchEffectService
    ) {
        this.objectMapper = objectMapper;
        this.matchEffectService = matchEffectService;
    }

    public Map<String, Object> applyArtDamage(
        Long matchId,
        Long userId,
        int baseDamage,
        Long targetHolomemCardInstanceId
    ) {
        return applyArtDamage(matchId, userId, baseDamage, targetHolomemCardInstanceId, false);
    }

    public Map<String, Object> applyArtDamage(
        Long matchId,
        Long userId,
        int baseDamage,
        Long targetHolomemCardInstanceId,
        boolean deferDownEvent
    ) {
        if (baseDamage <= 0) {
            throw new IllegalArgumentException("藝能傷害必須大於 0");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "DAMAGE");
        payload.put("value", baseDamage);
        payload.put("deferDownEvent", deferDownEvent);
        JsonNode effectNode = objectMapper.valueToTree(payload);
        return matchEffectService.executeDamageEffect(
            matchId,
            userId,
            "ART_DAMAGE",
            effectNode,
            "ENEMY",
            targetHolomemCardInstanceId
        );
    }

    public Map<String, Object> previewDownEventEffect(
        Long matchId,
        Long actorUserId,
        Long downedOwnerUserId,
        String downedCardId,
        int currentTurn
    ) {
        return matchEffectService.executeDownEvent(
            matchId,
            actorUserId,
            downedOwnerUserId,
            downedCardId,
            currentTurn,
            false,
            null
        );
    }

    public Map<String, Object> applyDownEventEffect(
        Long matchId,
        Long actorUserId,
        Long downedOwnerUserId,
        String downedCardId,
        int currentTurn
    ) {
        return applyDownEventEffect(matchId, actorUserId, downedOwnerUserId, downedCardId, currentTurn, null);
    }

    public Map<String, Object> applyDownEventEffect(
        Long matchId,
        Long actorUserId,
        Long downedOwnerUserId,
        String downedCardId,
        int currentTurn,
        String downedStageZone
    ) {
        return matchEffectService.executeDownEvent(
            matchId,
            actorUserId,
            downedOwnerUserId,
            downedCardId,
            currentTurn,
            true,
            downedStageZone
        );
    }
}
