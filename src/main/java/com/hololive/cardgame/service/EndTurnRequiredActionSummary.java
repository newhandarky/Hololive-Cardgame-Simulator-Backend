package com.hololive.cardgame.service;

import java.util.List;

public record EndTurnRequiredActionSummary(
    boolean drawTurnCompleted,
    boolean requiresTurnCheer,
    boolean turnCheerCompleted,
    List<String> missingActions
) {

    public boolean isComplete() {
        return missingActions == null || missingActions.isEmpty();
    }

    public String toFailureMessage() {
        if (isComplete()) {
            return "回合必要動作已完成";
        }
        return "回合尚未完成：" + String.join("、", missingActions) + "。請先完成後再結束回合";
    }
}
