package com.hololive.cardgame.service;

/**
 * command handler 共用的對戰與操作者識別資訊。
 */
public record MatchCommandContext(long matchId, long actorUserId) {

    public MatchCommandContext {
        if (matchId <= 0) {
            throw new IllegalArgumentException("matchId 必須為正整數");
        }
        if (actorUserId <= 0) {
            throw new IllegalArgumentException("actorUserId 必須為正整數");
        }
    }
}
