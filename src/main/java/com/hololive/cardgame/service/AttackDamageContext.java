package com.hololive.cardgame.service;

public record AttackDamageContext(
    Long matchId,
    Long attackerUserId,
    Long opponentUserId,
    int turnNumber,
    Long attackerHolomemId,
    String attackerLevel,
    AttackTargetHolomem target,
    boolean hasOpponentHolomem,
    String artEffectJsonText,
    int holoxRevealArtBonus
) {
    public static AttackDamageContext resolve(
        Long matchId,
        Long attackerUserId,
        Long opponentUserId,
        int turnNumber,
        Long attackerHolomemId,
        String attackerLevel,
        AttackTargetHolomem target,
        boolean hasOpponentHolomem,
        String artEffectJsonText,
        int holoxRevealArtBonus
    ) {
        return new AttackDamageContext(
            matchId,
            attackerUserId,
            opponentUserId,
            turnNumber,
            attackerHolomemId,
            attackerLevel,
            target,
            hasOpponentHolomem,
            artEffectJsonText,
            holoxRevealArtBonus
        );
    }
}
