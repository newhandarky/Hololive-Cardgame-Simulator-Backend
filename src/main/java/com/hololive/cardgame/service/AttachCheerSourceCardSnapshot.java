package com.hololive.cardgame.service;

public record AttachCheerSourceCardSnapshot(
    Long cardInstanceId,
    String cardId,
    String zone,
    boolean cheerCard
) {
}
