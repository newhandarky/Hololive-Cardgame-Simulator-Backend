package com.hololive.cardgame.game.action;

public record EffectContext(
    Long matchId,
    Long actorUserId,
    int turnNumber,
    String sourceActionType,
    Long sourceCardInstanceId,
    String sourceCardId
) {
    public static EffectContext system(Long matchId, Long actorUserId, String sourceActionType) {
        return new EffectContext(matchId, actorUserId, 0, sourceActionType, null, null);
    }
}
