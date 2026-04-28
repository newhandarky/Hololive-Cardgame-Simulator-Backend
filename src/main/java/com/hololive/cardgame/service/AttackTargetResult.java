package com.hololive.cardgame.service;

public record AttackTargetResult(
    boolean hasOpponentHolomem,
    AttackTargetHolomem target,
    Long effectiveTargetCardInstanceId,
    boolean passiveGiftTargetRestrictionToCollab,
    boolean passiveGiftTargetRestrictionApplied,
    boolean damageRedirectApplied,
    Long damageRedirectEffectId
) {

    public static AttackTargetResult noOpponent(Long requestedTargetCardInstanceId) {
        return new AttackTargetResult(
            false,
            null,
            requestedTargetCardInstanceId,
            false,
            false,
            false,
            null
        );
    }
}
