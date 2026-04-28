package com.hololive.cardgame.service;

public record AttackEffectDamagePreventionContext(
    Long matchId,
    Long attackerUserId,
    Long defenderUserId,
    Long attackerCardInstanceId,
    Long effectiveTargetCardInstanceId,
    int turnNumber,
    int totalDamage,
    boolean hasOpponentHolomem,
    boolean hasTargetHolomem
) {
    public static AttackEffectDamagePreventionContext attackArt(
        Long matchId,
        Long attackerUserId,
        Long defenderUserId,
        Long attackerCardInstanceId,
        Long effectiveTargetCardInstanceId,
        int turnNumber,
        int totalDamage,
        boolean hasOpponentHolomem,
        boolean hasTargetHolomem
    ) {
        return new AttackEffectDamagePreventionContext(
            matchId,
            attackerUserId,
            defenderUserId,
            attackerCardInstanceId,
            effectiveTargetCardInstanceId,
            turnNumber,
            totalDamage,
            hasOpponentHolomem,
            hasTargetHolomem
        );
    }
}
