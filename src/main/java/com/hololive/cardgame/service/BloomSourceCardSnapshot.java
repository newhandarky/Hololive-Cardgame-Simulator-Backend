package com.hololive.cardgame.service;

public record BloomSourceCardSnapshot(
    Long cardInstanceId,
    String cardId,
    String cardName,
    String levelType,
    int hp,
    String zone,
    boolean memberCard
) {
}
