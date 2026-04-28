package com.hololive.cardgame.service;

public record AttackFinishCheckResult(
    boolean finished,
    FinishType finishType,
    boolean saved
) {
    public enum FinishType {
        NONE,
        CARD_EFFECT,
        LIFE_DEFEAT,
        NO_HOLOMEM_DEFEAT
    }

    public static AttackFinishCheckResult none() {
        return new AttackFinishCheckResult(false, FinishType.NONE, false);
    }

    public static AttackFinishCheckResult finished(FinishType finishType, boolean saved) {
        return new AttackFinishCheckResult(true, finishType, saved);
    }
}
