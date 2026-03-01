package com.hololive.cardgame.game.action;

public record ReduceLifeAction(
    Long targetUserId,
    int amount,
    String reason
) implements AtomicAction {}
