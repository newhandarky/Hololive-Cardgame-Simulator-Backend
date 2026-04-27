package com.hololive.cardgame.service;

public record PlayCardSourceCardSnapshot(
    Long cardInstanceId,
    String cardId,
    String zone,
    boolean memberCard,
    String levelType
) {
}
