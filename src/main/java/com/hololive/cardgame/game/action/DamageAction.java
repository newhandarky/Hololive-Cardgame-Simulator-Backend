package com.hololive.cardgame.game.action;

public record DamageAction(
    Long targetHolomemId,
    int amount,
    String source
) implements AtomicAction {}
