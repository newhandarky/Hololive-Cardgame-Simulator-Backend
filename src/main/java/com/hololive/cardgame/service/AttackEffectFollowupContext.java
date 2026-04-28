package com.hololive.cardgame.service;

public record AttackEffectFollowupContext(
    Long matchId,
    Long attackerUserId,
    Long defenderUserId,
    int turnNumber,
    Long attackerHolomemId,
    String attackerCardId,
    String artName,
    String artEffectJsonText
) {
    public static AttackEffectFollowupContext preDamage(
        Long matchId,
        Long attackerUserId,
        Long defenderUserId,
        int turnNumber,
        Long attackerHolomemId,
        String attackerCardId,
        String artName,
        String artEffectJsonText
    ) {
        return new AttackEffectFollowupContext(
            matchId,
            attackerUserId,
            defenderUserId,
            turnNumber,
            attackerHolomemId,
            attackerCardId,
            artName,
            artEffectJsonText
        );
    }
}
