package com.hololive.cardgame.service;

import java.util.Map;

public record AttackEffectPostDamageContext(
    Long matchId,
    Long attackerUserId,
    Long defenderUserId,
    int turnNumber,
    Long attackerHolomemId,
    Long effectiveTargetCardInstanceId,
    String attackerCardId,
    String artName,
    String attackerMainColor,
    AttackTargetHolomem targetHolomem,
    Map<String, Object> artSummary
) {
    public static AttackEffectPostDamageContext attackArt(
        Long matchId,
        Long attackerUserId,
        Long defenderUserId,
        int turnNumber,
        Long attackerHolomemId,
        Long effectiveTargetCardInstanceId,
        String attackerCardId,
        String artName,
        String attackerMainColor,
        AttackTargetHolomem targetHolomem,
        Map<String, Object> artSummary
    ) {
        return new AttackEffectPostDamageContext(
            matchId,
            attackerUserId,
            defenderUserId,
            turnNumber,
            attackerHolomemId,
            effectiveTargetCardInstanceId,
            attackerCardId,
            artName,
            attackerMainColor,
            targetHolomem,
            artSummary
        );
    }
}
