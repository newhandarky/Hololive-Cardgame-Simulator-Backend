package com.hololive.cardgame.game.action;

public record UnimplementedAction(
    String effectType,
    String reason
) implements AtomicAction {}
