package com.hololive.cardgame.service;

/**
 * 執行目前玩家本回合抽牌的 typed command。
 */
public record DrawTurnCommand(long matchId, long actorUserId) implements MatchCommand {

    public MatchCommandContext context() {
        return new MatchCommandContext(matchId, actorUserId);
    }
}
