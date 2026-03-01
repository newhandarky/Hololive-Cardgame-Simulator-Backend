package com.hololive.cardgame.game.action;

public record HolomemMoveZoneAction(
    Long holomemId,
    String fromZone,
    String toZone,
    boolean restAfterMove
) implements AtomicAction {}
