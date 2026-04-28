package com.hololive.cardgame.service;

public record AttackActionLogContext(
    Long matchId,
    Long userId,
    int turnNumber,
    String payloadJson
) {
    public static AttackActionLogContext attackArt(
        Long matchId,
        Long userId,
        int turnNumber,
        String payloadJson
    ) {
        return new AttackActionLogContext(matchId, userId, turnNumber, payloadJson);
    }
}
