package com.hololive.cardgame.game.action;

public record SendCheerAction(
    Long cheerCardInstanceId,
    Long targetHolomemId,
    String source
) implements AtomicAction {}
