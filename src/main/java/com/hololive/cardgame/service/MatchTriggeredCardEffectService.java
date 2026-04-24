package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchTriggeredCardEffectService {

    private final ObjectMapper objectMapper;
    private final MatchEffectService matchEffectService;

    public MatchTriggeredCardEffectService(
        ObjectMapper objectMapper,
        MatchEffectService matchEffectService
    ) {
        this.objectMapper = objectMapper;
        this.matchEffectService = matchEffectService;
    }

    public Map<String, Object> applyPassiveGiftExtraBloomAllowanceOnBloom(
        Long matchId,
        Long userId,
        Long bloomedHolomemId,
        Long holderCardInstanceId,
        String holderCardId
    ) {
        if (matchId == null || userId == null || bloomedHolomemId == null || holderCardInstanceId == null) {
            return Map.of("effectType", "ALLOW_EXTRA_BLOOM", "applied", false, "reason", "缺少 Bloom 後靜態 Gift 所需參數");
        }

        String passiveText = matchEffectService.loadPassiveEffectText(holderCardId);
        String giftText = matchEffectService.loadGiftEffectText(passiveText);
        if (!StringUtils.hasText(giftText) || !giftText.contains("もう1回Bloomできる")) {
            return Map.of("effectType", "ALLOW_EXTRA_BLOOM", "applied", false, "reason", "此卡沒有額外 Bloom 的靜態 Gift");
        }

        ObjectNode effectNode = objectMapper.createObjectNode();
        effectNode.put("rawText", giftText);
        return matchEffectService.executeAllowExtraBloomEffect(
            matchId,
            userId,
            "ALLOW_EXTRA_BLOOM",
            effectNode,
            bloomedHolomemId,
            holderCardInstanceId
        );
    }

    public Map<String, Object> applyBloomTriggeredEffects(
        Long matchId,
        Long userId,
        String bloomCardId,
        Long selfHolomemCardInstanceId
    ) {
        return matchEffectService.applyBloomTriggeredEffects(matchId, userId, bloomCardId, selfHolomemCardInstanceId);
    }

    public Map<String, Object> applyBloomTriggeredEffects(
        Long matchId,
        Long userId,
        String bloomCardId,
        Long selfHolomemCardInstanceId,
        String sourceLevelType
    ) {
        return matchEffectService.applyBloomTriggeredEffects(
            matchId,
            userId,
            bloomCardId,
            selfHolomemCardInstanceId,
            sourceLevelType
        );
    }

    public Map<String, Object> applyCollabTriggeredEffects(
        Long matchId,
        Long userId,
        String collabCardId,
        Long selfHolomemCardInstanceId
    ) {
        return matchEffectService.applyCollabTriggeredEffects(matchId, userId, collabCardId, selfHolomemCardInstanceId);
    }

    public MatchEffectService.TriggeredEffectPreview previewBloomTriggeredEffect(String bloomCardId) {
        MatchEffectService.BloomEffectPlan bloomPlan = matchEffectService.resolveBloomEffectPlan(bloomCardId, null);
        return new MatchEffectService.TriggeredEffectPreview(
            bloomPlan.hasBloomEffect(),
            bloomPlan.effectTypes(),
            bloomPlan.rawText(),
            bloomPlan.diceRoll()
        );
    }

    public MatchEffectService.TriggeredEffectPreview previewBloomTriggeredEffect(
        Long matchId,
        Long userId,
        String bloomCardId,
        Long selfHolomemCardInstanceId,
        String sourceLevelType
    ) {
        MatchEffectService.BloomEffectPlan bloomPlan = matchEffectService.resolveBloomEffectPlan(
            bloomCardId,
            new MatchEffectService.BloomRuntimeContext(
                sourceLevelType,
                matchEffectService.loadCollabRuntimeContext(matchId, userId, selfHolomemCardInstanceId)
            )
        );
        return new MatchEffectService.TriggeredEffectPreview(
            bloomPlan.hasBloomEffect(),
            bloomPlan.effectTypes(),
            bloomPlan.rawText(),
            bloomPlan.diceRoll()
        );
    }

    public MatchEffectService.TriggeredEffectPreview previewCollabTriggeredEffect(
        Long matchId,
        Long userId,
        String collabCardId,
        Long selfHolomemCardInstanceId
    ) {
        MatchEffectService.CollabRuntimeContext runtimeContext = matchEffectService.loadCollabRuntimeContext(
            matchId,
            userId,
            selfHolomemCardInstanceId
        );
        MatchEffectService.BloomEffectPlan collabPlan = matchEffectService.resolveCollabEffectPlan(collabCardId, runtimeContext);
        return new MatchEffectService.TriggeredEffectPreview(
            collabPlan.hasBloomEffect(),
            collabPlan.effectTypes(),
            collabPlan.rawText(),
            collabPlan.diceRoll()
        );
    }

    public MatchEffectService.TriggeredEffectPreview previewCollabTriggeredEffect(String collabCardId) {
        return previewCollabTriggeredEffect(null, null, collabCardId, null);
    }
}
