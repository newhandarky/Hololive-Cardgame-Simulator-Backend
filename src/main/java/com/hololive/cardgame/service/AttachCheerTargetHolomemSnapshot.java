package com.hololive.cardgame.service;

public record AttachCheerTargetHolomemSnapshot(
    Long holomemId,
    Long cardInstanceId,
    String cardId,
    String zone
) {
}
