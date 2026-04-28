package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

public record AttackPostTriggerPendingContext(
    Long matchId,
    Long attackerUserId,
    Long defenderUserId,
    int turnNumber,
    Long attackerCardInstanceId,
    String attackerCardId,
    Long downedTargetCardInstanceId,
    String downedTargetCardId,
    List<Map<String, Object>> giftTriggeredEffects,
    Map<String, Object> downEventPreview,
    List<Map<String, Object>> defenderGiftTriggeredEffects
) {
    public static AttackPostTriggerPendingContext attackArt(
        Long matchId,
        Long attackerUserId,
        Long defenderUserId,
        int turnNumber,
        Long attackerCardInstanceId,
        String attackerCardId,
        Long downedTargetCardInstanceId,
        String downedTargetCardId,
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview,
        List<Map<String, Object>> defenderGiftTriggeredEffects
    ) {
        return new AttackPostTriggerPendingContext(
            matchId,
            attackerUserId,
            defenderUserId,
            turnNumber,
            attackerCardInstanceId,
            attackerCardId,
            downedTargetCardInstanceId,
            downedTargetCardId,
            giftTriggeredEffects,
            downEventPreview,
            defenderGiftTriggeredEffects
        );
    }
}
