package com.hololive.cardgame.service;

/**
 * 玩家投降並結束對戰的 command。
 */
public record ConcedeMatchCommand(long matchId, long actorUserId) implements MatchCommand {

    public MatchCommandContext context() {
        return new MatchCommandContext(matchId, actorUserId);
    }
}
