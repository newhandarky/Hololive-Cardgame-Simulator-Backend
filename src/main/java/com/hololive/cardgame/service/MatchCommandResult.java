package com.hololive.cardgame.service;

/**
 * command 成功處理後提供給 controller 的 application result。
 */
public record MatchCommandResult(long matchId, String eventType) {

    public MatchCommandResult {
        if (matchId <= 0) {
            throw new IllegalArgumentException("matchId 必須為正整數");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType 不可為空白");
        }
    }
}
