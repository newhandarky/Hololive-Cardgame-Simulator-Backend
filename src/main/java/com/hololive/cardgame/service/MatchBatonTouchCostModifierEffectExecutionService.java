package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchBatonTouchCostModifierEffectExecutionService {

    private static final Pattern BATON_TOUCH_COST_MODIFIER_PATTERN = Pattern.compile("バトンタッチに必要な無色\\s*[+＋]\\s*(\\d+)");

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final EffectTargetHolomemResolver effectTargetHolomemResolver;
    private final OpponentUserResolver opponentUserResolver;
    private final CurrentTurnResolver currentTurnResolver;
    private final HolomemOwnerResolver holomemOwnerResolver;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchBatonTouchCostModifierEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        EffectTargetHolomemResolver effectTargetHolomemResolver,
        OpponentUserResolver opponentUserResolver,
        CurrentTurnResolver currentTurnResolver,
        HolomemOwnerResolver holomemOwnerResolver,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.effectTargetHolomemResolver = effectTargetHolomemResolver;
        this.opponentUserResolver = opponentUserResolver;
        this.currentTurnResolver = currentTurnResolver;
        this.holomemOwnerResolver = holomemOwnerResolver;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

    /**
     * 套用バトンタッチ費用修正，寫入當回合效果表供後續行為讀取。
     */
    Map<String, Object> executeBatonTouchCostModifierEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        int modifier = effectTextParser.extractByPattern(rawText, BATON_TOUCH_COST_MODIFIER_PATTERN);
        if (modifier <= 0) {
            modifier = effectTextParser.extractInt(effectNode, 0, "modifier", "value", "amount");
        }
        if (modifier <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到有效的バトンタッチ無色修正值");
        }

        Long targetHolomemId = effectTargetHolomemResolver.resolve(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            Long ownerUserId = isOpponentTargetType(MatchEffectValueHelper.normalize(targetType))
                ? opponentUserResolver.resolve(matchId, userId)
                : userId;
            if (ownerUserId != null) {
                targetHolomemId = jdbcTemplate.query(
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
            }
        }
        if (targetHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可套用バトンタッチ修正的 CENTER 目標");
        }

        int currentTurn = currentTurnResolver.resolve(matchId);
        int expiresTurn = currentTurn + 1;
        int inserted = jdbcTemplate.update(
            """
            INSERT INTO match_turn_effects (
                match_id,
                source_user_id,
                affected_user_id,
                effect_type,
                stat_type,
                modifier_value,
                expires_turn,
                payload
            ) VALUES (?, ?, ?, ?, 'BATON_TOUCH_COLORLESS_MODIFIER', ?, ?, CAST(? AS jsonb))
            """,
            matchId,
            userId,
            holomemOwnerResolver.resolve(matchId, targetHolomemId),
            "DEBUFF",
            modifier,
            expiresTurn,
            effectTextParser.toJsonString(Map.of("targetHolomemId", targetHolomemId, "rawText", rawText))
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted == 1);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
        summary.put("modifierValue", modifier);
        summary.put("expiresTurn", expiresTurn);
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

    private boolean isOpponentTargetType(String targetType) {
        return targetType.contains("ENEMY") || targetType.contains("OPPONENT");
    }

    @FunctionalInterface
    interface EffectTargetHolomemResolver {
        Long resolve(
            Long matchId,
            Long userId,
            String targetType,
            Long targetHolomemCardInstanceId,
            boolean allowOpponent
        );
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
    interface HolomemOwnerResolver {
        Long resolve(Long matchId, Long holomemId);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
