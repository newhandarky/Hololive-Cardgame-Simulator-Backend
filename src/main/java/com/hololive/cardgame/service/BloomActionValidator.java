package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BloomActionValidator {

    public BloomValidationResult validate(BloomAction action, BloomValidationContext context) {
        if (action == null || context == null || context.match() == null) {
            throw new IllegalArgumentException("BLOOM 驗證缺少必要上下文");
        }
        if (!isMatchActive(context.matchStatus(), context.lobbyStatus())) {
            return BloomValidationResult.blocked(GameErrorCode.CONFLICT, "對戰目前不可執行 BLOOM");
        }
        if (action.requestedTurnNumber() != context.currentTurnNumber()) {
            return BloomValidationResult.blocked(
                GameErrorCode.STALE_ACTION,
                "這個 BLOOM 請求已過期，請重新整理目前對戰狀態後再試一次"
            );
        }
        if (context.duplicateAction()) {
            return BloomValidationResult.blocked(GameErrorCode.DUPLICATE_ACTION, "這個 BLOOM 請求已經執行過");
        }
        if (!action.actorUserId().equals(context.currentTurnPlayerId())) {
            return BloomValidationResult.blocked(GameErrorCode.NOT_YOUR_TURN, "尚未輪到你執行 BLOOM");
        }
        if (context.currentPhase() != MatchPhase.MAIN) {
            return BloomValidationResult.blocked(GameErrorCode.PHASE_ACTION_NOT_ALLOWED, "目前階段不可執行 BLOOM");
        }
        if (context.actorPendingInteractions()) {
            return BloomValidationResult.blocked(
                GameErrorCode.PENDING_INTERACTION_BLOCKED,
                "請先處理目前等待中的效果確認"
            );
        }
        BloomSourceCardSnapshot sourceCard = context.sourceCard();
        if (sourceCard == null) {
            return BloomValidationResult.blocked(GameErrorCode.NOT_FOUND, "找不到要 BLOOM 的手牌");
        }
        if (!"HAND".equals(normalize(sourceCard.zone()))) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "BLOOM 卡必須從手牌使用");
        }
        if (!sourceCard.memberCard()) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "只有 MEMBER 卡可以執行 BLOOM");
        }
        if (isSpecialOrUnbloomableLevel(sourceCard.levelType())) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "此卡不可作為 BLOOM 卡");
        }
        if (sourceCard.hp() <= 0) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "BLOOM 卡片缺少有效 HP");
        }
        BloomTargetSnapshot target = context.target();
        if (target == null) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_NO_TARGET, "找不到要 BLOOM 的目標 Holomem");
        }
        if (target.stageActionLocked()) {
            return BloomValidationResult.blocked(GameErrorCode.STAGE_ACTION_LOCKED, "目前效果限制：不可 Bloom");
        }
        if (isSpecialOrUnbloomableLevel(target.topLevelType())) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "Spot Holomem 不能作為 BLOOM 目標");
        }
        if (target.enteredTurnNumber() != null && target.enteredTurnNumber() == context.currentTurnNumber()) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "本回合剛上場的 Holomem 不能 BLOOM");
        }
        if (
            target.lastBloomTurn() != null &&
                target.lastBloomTurn() == context.currentTurnNumber() &&
                target.extraBloomAllowanceId() == null
        ) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "此 Holomem 本回合已執行過 BLOOM");
        }
        if (!StringUtils.hasText(target.topCardName()) || !target.topCardName().equals(sourceCard.cardName())) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "BLOOM 需要與目標 Holomem 同名");
        }
        if (!isBloomLevelNextStep(target.topLevelType(), sourceCard.levelType()) && !target.levelOverrideAllowed()) {
            return BloomValidationResult.blocked(
                GameErrorCode.BLOOM_INVALID_TARGET,
                "BLOOM 只能依序遞進：DEBUT→FIRST、FIRST→SECOND、SECOND→BUZZ"
            );
        }
        if (sourceCard.hp() < target.damageTaken()) {
            return BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "BLOOM 卡 HP 不足以承受目標目前傷害");
        }
        return BloomValidationResult.permitted();
    }

    private boolean isBloomLevelNextStep(String fromLevelType, String toLevelType) {
        String from = normalize(fromLevelType);
        String to = normalize(toLevelType);
        return ("DEBUT".equals(from) && "FIRST".equals(to)) ||
            ("FIRST".equals(from) && "SECOND".equals(to)) ||
            ("SECOND".equals(from) && "BUZZ".equals(to));
    }

    private boolean isSpecialOrUnbloomableLevel(String levelType) {
        String level = normalize(levelType);
        return level.isBlank() || "SPOT".equals(level) || "OSHI".equals(level);
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
