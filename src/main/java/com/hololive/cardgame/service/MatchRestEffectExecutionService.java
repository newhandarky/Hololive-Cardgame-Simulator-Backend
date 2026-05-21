package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchRestEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final DiceConditionChecker diceConditionChecker;
    private final TargetHolomemResolver targetHolomemResolver;
    private final OpponentUserResolver opponentUserResolver;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchRestEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        DiceConditionChecker diceConditionChecker,
        TargetHolomemResolver targetHolomemResolver,
        OpponentUserResolver opponentUserResolver,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.diceConditionChecker = diceConditionChecker;
        this.targetHolomemResolver = targetHolomemResolver;
        this.opponentUserResolver = opponentUserResolver;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

    /**
     * 執行休息效果（將目標 Holomem 設為 rested）。
     */
    Map<String, Object> executeRestEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        Long targetHolomemId = targetHolomemResolver.resolve(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null && rawText.contains("バックホロメン")) {
            Long ownerUserId = isOpponentTargetType(targetType)
                ? opponentUserResolver.resolve(matchId, userId)
                : userId;
            if (ownerUserId != null) {
                targetHolomemId = jdbcTemplate.query(
                    """
                    SELECT id
                    FROM match_holomems
                    WHERE match_id = ?
                      AND owner_user_id = ?
                      AND zone = 'BACK'
                    ORDER BY id
                    LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    matchId,
                    ownerUserId
                );
            }
        }
        if (targetHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可設為休息的 Holomem");
        }

        int updated = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
            """,
            targetHolomemId,
            matchId
        );
        if (updated != 1) {
            return executeNoOpEffect(effectType, effectNode, "設定休息狀態失敗");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
        summary.put("rested", true);
        return summary;
    }

    private boolean isOpponentTargetType(String targetType) {
        String normalized = MatchEffectValueHelper.normalize(targetType);
        return "OPPONENT".equals(normalized)
            || "ENEMY".equals(normalized)
            || "OPPONENT_CENTER".equals(normalized)
            || "OPPONENT_BACK".equals(normalized)
            || "OPPONENT_COLLAB".equals(normalized);
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
    interface TargetHolomemResolver {
        Long resolve(
            Long matchId,
            Long userId,
            String targetType,
            Long targetHolomemCardInstanceId,
            boolean defaultOpponent
        );
    }

    @FunctionalInterface
    interface OpponentUserResolver {
        Long resolve(Long matchId, Long userId);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
