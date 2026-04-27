package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PlayCardActionValidator {

    private static final Set<String> ALLOWED_TARGET_ZONES = Set.of("CENTER", "BACK");
    private static final Set<String> PLAYABLE_LEVELS = Set.of("DEBUT", "SPOT");

    public PlayCardValidationResult validate(
        PlayCardAction action,
        PlayCardValidationContext context
    ) {
        if (action == null || context == null || context.match() == null) {
            throw new IllegalArgumentException("PLAY_CARD 驗證缺少必要上下文");
        }
        if (!isMatchActive(context.matchStatus(), context.lobbyStatus())) {
            return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "對戰目前不可放置 Holomem");
        }
        if (action.requestedTurnNumber() != context.currentTurnNumber()) {
            return PlayCardValidationResult.blocked(
                GameErrorCode.STALE_ACTION,
                "這個放置 Holomem 請求已過期，請重新整理目前對戰狀態後再試一次"
            );
        }
        if (context.duplicateAction()) {
            return PlayCardValidationResult.blocked(
                GameErrorCode.DUPLICATE_ACTION,
                "這個放置 Holomem 請求已經執行過"
            );
        }
        if (!action.actorUserId().equals(context.currentTurnPlayerId())) {
            return PlayCardValidationResult.blocked(GameErrorCode.NOT_YOUR_TURN, "尚未輪到你放置 Holomem");
        }
        if (context.currentPhase() != MatchPhase.RESET && context.currentPhase() != MatchPhase.MAIN) {
            return PlayCardValidationResult.blocked(
                GameErrorCode.PHASE_ACTION_NOT_ALLOWED,
                "目前階段不可放置 Holomem"
            );
        }
        if (context.actorPendingInteractions()) {
            return PlayCardValidationResult.blocked(
                GameErrorCode.PENDING_INTERACTION_BLOCKED,
                "請先處理目前等待中的效果確認"
            );
        }
        if (!isPositive(action.cardInstanceId())) {
            return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "cardInstanceId 必須為正數");
        }
        String targetZone = normalize(action.targetZone());
        if (!ALLOWED_TARGET_ZONES.contains(targetZone)) {
            return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "targetZone 只支援 CENTER 或 BACK");
        }
        if (context.stageActionLocked()) {
            return PlayCardValidationResult.blocked(GameErrorCode.STAGE_ACTION_LOCKED, "目前效果限制：不可放置 Holomem");
        }

        PlayCardSourceCardSnapshot sourceCard = context.sourceCard();
        if (sourceCard == null) {
            return PlayCardValidationResult.blocked(GameErrorCode.NOT_FOUND, "找不到要放置的手牌");
        }
        if (!"HAND".equals(normalize(sourceCard.zone()))) {
            return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "只能從手牌放置 Holomem");
        }
        if (!sourceCard.memberCard()) {
            return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "只有 MEMBER 卡可以打到場上");
        }

        String levelType = normalize(sourceCard.levelType());
        if (context.currentPhase() == MatchPhase.RESET) {
            PlayCardValidationResult openingResult = validateOpeningPlacement(context, targetZone, levelType);
            if (!openingResult.allowed()) {
                return openingResult;
            }
        } else {
            if (!PLAYABLE_LEVELS.contains(levelType)) {
                return PlayCardValidationResult.blocked(
                    GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED,
                    "只有 DEBUT 或 SPOT Holomem 可以從手牌放置到場上；FIRST/SECOND/BUZZ 請改用 BLOOM"
                );
            }
            if (!"BACK".equals(targetZone)) {
                return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "手牌 Holomem 只能放置到 BACK");
            }
        }

        if ("BACK".equals(targetZone) && context.targetZoneOccupiedCount() >= 5) {
            return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "BACK 已滿（最多 5 張）");
        }
        return PlayCardValidationResult.permitted();
    }

    private PlayCardValidationResult validateOpeningPlacement(
        PlayCardValidationContext context,
        String targetZone,
        String levelType
    ) {
        if (!context.actorMulliganDone()) {
            return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "請先完成起手調度，再設置開場舞台");
        }
        if (!context.openingCenterPlaced()) {
            if (!"DEBUT".equals(levelType)) {
                return PlayCardValidationResult.blocked(
                    GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED,
                    "開場只能從手牌放置 DEBUT Holomem 到 CENTER"
                );
            }
            if (!"CENTER".equals(targetZone)) {
                return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "放置開場 CENTER 前，不能先設置開場 BACK");
            }
            return PlayCardValidationResult.permitted();
        }
        if (!PLAYABLE_LEVELS.contains(levelType)) {
            return PlayCardValidationResult.blocked(
                GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED,
                "開場 BACK 只能放置 DEBUT 或 SPOT Holomem"
            );
        }
        if (!"BACK".equals(targetZone)) {
            return PlayCardValidationResult.blocked(GameErrorCode.CONFLICT, "開場完成 CENTER 後，只能繼續設置 BACK");
        }
        return PlayCardValidationResult.permitted();
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
