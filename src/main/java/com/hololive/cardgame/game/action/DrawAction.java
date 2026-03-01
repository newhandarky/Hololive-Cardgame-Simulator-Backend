package com.hololive.cardgame.game.action;

public record DrawAction(
    Long ownerUserId,
    int drawCount,
    String fromZone,
    String toZone
) implements AtomicAction {}
