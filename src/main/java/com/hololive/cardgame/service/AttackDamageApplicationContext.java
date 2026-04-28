package com.hololive.cardgame.service;

public record AttackDamageApplicationContext(
    Long matchId,
    Long attackerUserId,
    Long opponentUserId,
    int finalDamage,
    Long effectiveTargetCardInstanceId,
    boolean hasOpponentHolomem,
    boolean deferDownEvent
) {
    public static AttackDamageApplicationContext attackArt(
        Long matchId,
        Long attackerUserId,
        Long opponentUserId,
        int finalDamage,
        Long effectiveTargetCardInstanceId,
        boolean hasOpponentHolomem
    ) {
        return new AttackDamageApplicationContext(
            matchId,
            attackerUserId,
            opponentUserId,
            finalDamage,
            effectiveTargetCardInstanceId,
            hasOpponentHolomem,
            true
        );
    }
}
