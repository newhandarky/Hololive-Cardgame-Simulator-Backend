package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

public record AttackDefenderGiftFollowupContext(
    Long matchId,
    Long defenderUserId,
    Long attackerUserId,
    int turnNumber,
    boolean hasDownedHolomem,
    Long downedTargetCardInstanceId,
    String downedTargetCardId,
    String downedTargetZone,
    AttackTargetHolomem downedTarget,
    Map<String, Object> holderSnapshot,
    List<Map<String, Object>> fanSupportSnapshots,
    Map<String, Object> artSummary
) {
    public static AttackDefenderGiftFollowupContext attackArt(
        Long matchId,
        Long defenderUserId,
        Long attackerUserId,
        int turnNumber,
        boolean hasDownedHolomem,
        Long downedTargetCardInstanceId,
        String downedTargetCardId,
        String downedTargetZone,
        AttackTargetHolomem downedTarget,
        Map<String, Object> holderSnapshot,
        List<Map<String, Object>> fanSupportSnapshots,
        Map<String, Object> artSummary
    ) {
        return new AttackDefenderGiftFollowupContext(
            matchId,
            defenderUserId,
            attackerUserId,
            turnNumber,
            hasDownedHolomem,
            downedTargetCardInstanceId,
            downedTargetCardId,
            downedTargetZone,
            downedTarget,
            holderSnapshot,
            fanSupportSnapshots,
            artSummary
        );
    }
}
