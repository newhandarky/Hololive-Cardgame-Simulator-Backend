package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

final class MatchGiftEffectServiceHandlers implements MatchGiftEffectDispatcher.GiftEffectHandlers {

    private final MatchEffectService effectService;
    private final MatchGiftArchiveReturnEffectExecutionService archiveReturnEffectExecutionService;

    MatchGiftEffectServiceHandlers(
        MatchEffectService effectService,
        MatchGiftArchiveReturnEffectExecutionService archiveReturnEffectExecutionService
    ) {
        this.effectService = effectService;
        this.archiveReturnEffectExecutionService = archiveReturnEffectExecutionService;
    }

    @Override
    public Map<String, Object> executeReplaceArchiveWithHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long holderCardInstanceId
    ) {
        return archiveReturnEffectExecutionService.executeReplaceArchiveWithHandEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            holderCardInstanceId
        );
    }

    @Override
    public Map<String, Object> executeAddCheerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        return effectService.executeAddCheerEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            targetType,
            targetHolomemCardInstanceId
        );
    }

    @Override
    public Map<String, Object> executeDamageEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long requestedTargetCardInstanceId
    ) {
        return effectService.executeDamageEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            targetType,
            requestedTargetCardInstanceId
        );
    }

    @Override
    public Map<String, Object> executeReattachEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long sourceHolomemCardInstanceId
    ) {
        return effectService.executeReattachEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            targetType,
            sourceHolomemCardInstanceId
        );
    }

    @Override
    public Map<String, Object> executeArchiveStackCardEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long holderCardInstanceId
    ) {
        return effectService.executeArchiveStackCardEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            holderCardInstanceId
        );
    }

    @Override
    public Map<String, Object> executeBuffDebuffEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType
    ) {
        return effectService.executeBuffDebuffEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            targetType
        );
    }

    @Override
    public Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        return effectService.executeNoOpEffect(effectType, effectNode, reason);
    }
}
