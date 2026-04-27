package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AttachCheerActionValidator {

    private static final Set<String> ALLOWED_SOURCE_ZONES = Set.of("HAND", "CHEER_DECK");

    public AttachCheerValidationResult validate(
        AttachCheerAction action,
        AttachCheerValidationContext context
    ) {
        if (action == null || context == null || context.match() == null) {
            throw new IllegalArgumentException("ATTACH_CHEER 驗證缺少必要上下文");
        }
        if (!isMatchActive(context.matchStatus(), context.lobbyStatus())) {
            return AttachCheerValidationResult.blocked(GameErrorCode.CONFLICT, "對戰目前不可附加 Cheer");
        }
        if (action.requestedTurnNumber() != context.currentTurnNumber()) {
            return AttachCheerValidationResult.blocked(
                GameErrorCode.STALE_ACTION,
                "這個附加 Cheer 請求已過期，請重新整理目前對戰狀態後再試一次"
            );
        }
        if (context.duplicateAction()) {
            return AttachCheerValidationResult.blocked(
                GameErrorCode.DUPLICATE_ACTION,
                "這個附加 Cheer 請求已經執行過"
            );
        }
        if (!action.actorUserId().equals(context.currentTurnPlayerId())) {
            return AttachCheerValidationResult.blocked(GameErrorCode.NOT_YOUR_TURN, "尚未輪到你附加 Cheer");
        }
        if (context.currentPhase() != MatchPhase.MAIN) {
            return AttachCheerValidationResult.blocked(
                GameErrorCode.PHASE_ACTION_NOT_ALLOWED,
                "目前階段不可附加 Cheer"
            );
        }
        if (context.actorPendingInteractions()) {
            return AttachCheerValidationResult.blocked(
                GameErrorCode.PENDING_INTERACTION_BLOCKED,
                "請先處理目前等待中的效果確認"
            );
        }
        if (!isPositive(action.cheerCardInstanceId())) {
            return AttachCheerValidationResult.blocked(
                GameErrorCode.ATTACH_CHEER_INVALID_TARGET,
                "cheerCardInstanceId 必須為正數"
            );
        }
        if (!isPositive(action.targetHolomemCardInstanceId())) {
            return AttachCheerValidationResult.blocked(
                GameErrorCode.ATTACH_CHEER_INVALID_TARGET,
                "targetHolomemCardInstanceId 必須為正數"
            );
        }
        if (context.stageActionLocked()) {
            return AttachCheerValidationResult.blocked(GameErrorCode.STAGE_ACTION_LOCKED, "目前效果限制：不可附加 Cheer");
        }

        AttachCheerSourceCardSnapshot sourceCard = context.sourceCard();
        if (sourceCard == null) {
            return AttachCheerValidationResult.blocked(GameErrorCode.NOT_FOUND, "找不到要附加的 Cheer");
        }
        if (!ALLOWED_SOURCE_ZONES.contains(normalize(sourceCard.zone()))) {
            return AttachCheerValidationResult.blocked(
                GameErrorCode.ATTACH_CHEER_INVALID_TARGET,
                "Cheer 只能從 HAND 或 CHEER_DECK 附加"
            );
        }
        if (!sourceCard.cheerCard()) {
            return AttachCheerValidationResult.blocked(
                GameErrorCode.ATTACH_CHEER_INVALID_TARGET,
                "指定卡片不是 Cheer 卡"
            );
        }

        AttachCheerTargetHolomemSnapshot targetHolomem = context.targetHolomem();
        if (targetHolomem == null) {
            return AttachCheerValidationResult.blocked(GameErrorCode.NOT_FOUND, "找不到要附加 Cheer 的 Holomem");
        }
        return AttachCheerValidationResult.permitted();
    }

    private boolean isMatchActive(String matchStatus, String lobbyStatus) {
        String normalizedMatchStatus = normalize(matchStatus);
        String normalizedLobbyStatus = normalize(lobbyStatus);
        return "ACTIVE".equals(normalizedMatchStatus) &&
            ("STARTED".equals(normalizedLobbyStatus) || "ACTIVE".equals(normalizedLobbyStatus));
    }

    private boolean isPositive(Long value) {
        return value != null && value > 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
