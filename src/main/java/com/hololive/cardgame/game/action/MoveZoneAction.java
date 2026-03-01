package com.hololive.cardgame.game.action;

public record MoveZoneAction(
    Long cardInstanceId,
    Long ownerUserId,
    String fromZone,
    String toZone,
    Integer targetOrderIndex,
    Boolean faceDown
) implements AtomicAction {}
