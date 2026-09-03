package com.hololive.cardgame.service;

/**
 * 建立本回合 Cheer 選擇互動的 typed command。
 */
public record SendTurnCheerCommand(long matchId, long actorUserId) implements MatchCommand {

    public MatchCommandContext context() {
        return new MatchCommandContext(matchId, actorUserId);
    }
}
