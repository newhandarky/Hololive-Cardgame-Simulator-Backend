package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchSwapCenterBackEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final DiceConditionChecker diceConditionChecker;
    private final OpponentUserResolver opponentUserResolver;
    private final CurrentTurnResolver currentTurnResolver;
    private final ActionLockChecker actionLockChecker;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchSwapCenterBackEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        DiceConditionChecker diceConditionChecker,
        OpponentUserResolver opponentUserResolver,
        CurrentTurnResolver currentTurnResolver,
        ActionLockChecker actionLockChecker,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.diceConditionChecker = diceConditionChecker;
        this.opponentUserResolver = opponentUserResolver;
        this.currentTurnResolver = currentTurnResolver;
        this.actionLockChecker = actionLockChecker;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

    /**
     * 執行 CENTER/BACK 交換效果。
     */
    Map<String, Object> executeSwapCenterBackEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        boolean targetOpponent = rawText.contains("相手の");
        boolean requireBackNotRested = rawText.contains("お休みしていない");
        Long ownerUserId = targetOpponent ? opponentUserResolver.resolve(matchId, userId) : userId;
        if (ownerUserId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到交換目標玩家");
        }

        Long centerHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId
        );
        if (centerHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有可交換的 CENTER");
        }
        int currentTurn = currentTurnResolver.resolve(matchId);
        if (actionLockChecker.isActive(matchId, ownerUserId, currentTurn, "SWAP", "CENTER", centerHolomemId)) {
            return executeNoOpEffect(effectType, effectNode, "目前效果限制：不可交代");
        }

        Long backHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
              AND (? = FALSE OR is_rested = FALSE)
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            requireBackNotRested
        );
        if (backHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有可交換的 BACK");
        }
        if (actionLockChecker.isActive(matchId, ownerUserId, currentTurn, "SWAP", "BACK", backHolomemId)) {
            return executeNoOpEffect(effectType, effectNode, "目前效果限制：不可交代");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'BACK',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
            """,
            centerHolomemId,
            matchId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'CENTER',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
            """,
            backHolomemId,
            matchId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetOwnerUserId", ownerUserId);
        summary.put("fromCenterHolomemId", centerHolomemId);
        summary.put("fromBackHolomemId", backHolomemId);
        summary.put("centerHolomemCardInstanceId", holomemCardInstanceResolver.resolve(backHolomemId));
        summary.put("backHolomemCardInstanceId", holomemCardInstanceResolver.resolve(centerHolomemId));
        return summary;
    }

    private Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        summary.put("rawText", effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        return summary;
    }

    @FunctionalInterface
    interface DiceConditionChecker {
        boolean shouldApply(String rawText, JsonNode effectNode, String effectType);
    }

    @FunctionalInterface
    interface OpponentUserResolver {
        Long resolve(Long matchId, Long userId);
    }

    @FunctionalInterface
    interface CurrentTurnResolver {
        int resolve(Long matchId);
    }

    @FunctionalInterface
    interface ActionLockChecker {
        boolean isActive(Long matchId, Long userId, int turnNumber, String action, String zone, Long holomemId);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
