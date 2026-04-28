package com.hololive.cardgame.service;

public record AttackPendingDecision(
    Long decisionId,
    String decisionType
) {
    public boolean hasDecision() {
        return decisionId != null && decisionId > 0;
    }
}
