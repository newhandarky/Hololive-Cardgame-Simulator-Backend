package com.hololive.cardgame.service;

public record CollabSourceHolomemSnapshot(
    Long holomemId,
    Long cardInstanceId,
    String cardId,
    String zone,
    boolean rested
) {
}
