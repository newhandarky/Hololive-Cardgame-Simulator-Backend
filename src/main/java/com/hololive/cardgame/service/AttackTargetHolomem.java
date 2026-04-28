package com.hololive.cardgame.service;

public record AttackTargetHolomem(
    Long holomemId,
    Long matchCardInstanceId,
    String cardId,
    String zone,
    String mainColor
) {}
