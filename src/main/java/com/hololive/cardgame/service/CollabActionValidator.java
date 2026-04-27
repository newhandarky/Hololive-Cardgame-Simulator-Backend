package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import org.springframework.stereotype.Service;

@Service
public class CollabActionValidator {

    public CollabValidationResult validate(CollabAction action, CollabValidationContext context) {
        if (action == null || context == null || context.match() == null) {
            throw new IllegalArgumentException("COLLAB 驗證缺少必要上下文");
        }
        if (!isMatchActive(context.matchStatus(), context.lobbyStatus())) {
            return CollabValidationResult.blocked(GameErrorCode.CONFLICT, "對戰目前不可執行連動");
        }
        if (action.requestedTurnNumber() != context.currentTurnNumber()) {
            return CollabValidationResult.blocked(
                GameErrorCode.STALE_ACTION,
                "這個連動請求已過期，請重新整理目前對戰狀態後再試一次"
            );
        }
        if (context.duplicateAction()) {
            return CollabValidationResult.blocked(GameErrorCode.DUPLICATE_ACTION, "這個連動請求已經執行過");
        }
        if (!action.actorUserId().equals(context.currentTurnPlayerId())) {
            return CollabValidationResult.blocked(GameErrorCode.NOT_YOUR_TURN, "尚未輪到你執行連動");
        }
        if (context.currentPhase() != MatchPhase.MAIN) {
            return CollabValidationResult.blocked(GameErrorCode.PHASE_ACTION_NOT_ALLOWED, "目前階段不可執行連動");
        }
        if (context.actorPendingInteractions()) {
            return CollabValidationResult.blocked(
                GameErrorCode.PENDING_INTERACTION_BLOCKED,
                "請先處理目前等待中的效果確認"
            );
        }
        if (!"COLLAB".equals(normalize(action.targetZone()))) {
            return CollabValidationResult.blocked(GameErrorCode.COLLAB_INVALID_TARGET, "targetZone 只支援 COLLAB");
        }
        if (context.stageActionLocked()) {
            return CollabValidationResult.blocked(GameErrorCode.STAGE_ACTION_LOCKED, "目前效果限制：不可移動");
        }
        CollabSourceHolomemSnapshot sourceHolomem = context.sourceHolomem();
        if (sourceHolomem == null) {
            return CollabValidationResult.blocked(GameErrorCode.NOT_FOUND, "找不到指定的場上 Holomem");
        }
        String sourceZone = normalize(sourceHolomem.zone());
        if (!"BACK".equals(sourceZone)) {
            return CollabValidationResult.blocked(GameErrorCode.COLLAB_INVALID_TARGET, "目前只支援從 BACK 移動 Holomem");
        }
        if (sourceHolomem.rested()) {
            return CollabValidationResult.blocked(GameErrorCode.COLLAB_INVALID_TARGET, "休息中的 Holomem 不能執行連動");
        }
        if (context.targetZoneOccupiedCount() > 0) {
            return CollabValidationResult.blocked(GameErrorCode.COLLAB_INVALID_TARGET, "COLLAB 已有 Holomem");
        }
        if (context.collabUsedThisTurn()) {
            return CollabValidationResult.blocked(GameErrorCode.COLLAB_INVALID_TARGET, "本回合已執行過連動");
        }
        return CollabValidationResult.permitted();
    }

    private boolean isMatchActive(String matchStatus, String lobbyStatus) {
        String normalizedMatchStatus = normalize(matchStatus);
        String normalizedLobbyStatus = normalize(lobbyStatus);
        return "ACTIVE".equals(normalizedMatchStatus) &&
            ("STARTED".equals(normalizedLobbyStatus) || "ACTIVE".equals(normalizedLobbyStatus));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
