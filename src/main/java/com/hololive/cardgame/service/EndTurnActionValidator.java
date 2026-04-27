package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EndTurnActionValidator {

    public EndTurnValidationResult validate(EndTurnAction action, EndTurnValidationContext context) {
        if (action == null || context == null || context.match() == null) {
            throw new IllegalArgumentException("END_TURN 驗證缺少必要上下文");
        }
        if (action.requestedTurnNumber() != context.currentTurnNumber()) {
            return EndTurnValidationResult.blocked(
                GameErrorCode.STALE_ACTION,
                "這個 END_TURN 請求已過期，請重新整理目前對戰狀態後再試一次",
                Map.of(
                    "requestedTurnNumber", action.requestedTurnNumber(),
                    "currentTurnNumber", context.currentTurnNumber()
                )
            );
        }
        if (context.duplicateAction()) {
            return EndTurnValidationResult.blocked(
                GameErrorCode.DUPLICATE_ACTION,
                "這個 END_TURN 請求已經執行過"
            );
        }
        if (!context.actorUserId().equals(context.currentTurnPlayerId())) {
            return EndTurnValidationResult.blocked(
                GameErrorCode.NOT_YOUR_TURN,
                "現在不是你的回合"
            );
        }
        if (context.currentPhase() != com.hololive.cardgame.model.MatchPhase.END) {
            return EndTurnValidationResult.blocked(
                GameErrorCode.PHASE_ACTION_NOT_ALLOWED,
                "目前 phase=" + context.currentPhase() + "，無法執行此操作",
                Map.of("phase", context.currentPhase().name())
            );
        }
        if (context.actorPendingInteractions()) {
            return EndTurnValidationResult.blocked(
                GameErrorCode.PENDING_INTERACTION_BLOCKED,
                "你有待處理的互動，請先完成確認"
            );
        }
        if (context.anyPendingInteractions()) {
            return EndTurnValidationResult.blocked(
                GameErrorCode.PENDING_INTERACTION_BLOCKED,
                "對戰中有待處理的互動，請先完成確認"
            );
        }
        if (!context.requiredTurnActionSummary().isComplete()) {
            return EndTurnValidationResult.blocked(
                GameErrorCode.TURN_ACTIONS_INCOMPLETE,
                context.requiredTurnActionSummary().toFailureMessage(),
                Map.of(
                    "missingActions",
                    context.requiredTurnActionSummary().missingActions()
                )
            );
        }
        return EndTurnValidationResult.permitted();
    }
}
