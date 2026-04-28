package com.hololive.cardgame.service;

public record AttackTargetContext(
    Long matchId,
    Long attackerUserId,
    Long opponentUserId,
    int turnNumber,
    Long requestedTargetCardInstanceId,
    boolean resolveDamageRedirect
) {

    public static AttackTargetContext resolve(
        Long matchId,
        Long attackerUserId,
        Long opponentUserId,
        int turnNumber,
        Long requestedTargetCardInstanceId
    ) {
        return new AttackTargetContext(
            matchId,
            attackerUserId,
            opponentUserId,
            turnNumber,
            requestedTargetCardInstanceId,
            true
        );
    }
}
