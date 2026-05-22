package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchActionLockEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final OpponentUserResolver opponentUserResolver;
    private final CurrentTurnResolver currentTurnResolver;
    private final EffectTargetHolomemResolver effectTargetHolomemResolver;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchActionLockEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        OpponentUserResolver opponentUserResolver,
        CurrentTurnResolver currentTurnResolver,
        EffectTargetHolomemResolver effectTargetHolomemResolver,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.opponentUserResolver = opponentUserResolver;
        this.currentTurnResolver = currentTurnResolver;
        this.effectTargetHolomemResolver = effectTargetHolomemResolver;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

    /**
     * 寫入 ACTION_LOCK 封鎖效果（禁止指定動作/區位/目標）。
     */
    Map<String, Object> executeActionLockEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        List<String> actions = new ArrayList<>();
        if (rawText.contains("バトンタッチ") && rawText.contains("できない")) {
            actions.add("BATON_TOUCH");
        }
        if (rawText.contains("移動") && rawText.contains("できない")) {
            actions.add("MOVE_STAGE");
        }
        if (rawText.contains("交代") && rawText.contains("できない")) {
            actions.add("SWAP");
        }
        if ((rawText.contains("Bloom") || rawText.contains("ブルーム")) && rawText.contains("できない")) {
            actions.add("BLOOM");
        }
        if (rawText.contains("アクティブにならない")) {
            actions.add("UNREST");
        }
        if (actions.isEmpty()) {
            return executeNoOpEffect(effectType, effectNode, "無可套用的行動封鎖條件");
        }

        List<String> zones = new ArrayList<>();
        if (rawText.contains("センターホロメン")) {
            zones.add("CENTER");
        }
        if (rawText.contains("コラボホロメン")) {
            zones.add("COLLAB");
        }

        Long affectedUserId = rawText.contains("相手の") ? opponentUserResolver.resolve(matchId, userId) : userId;
        if (affectedUserId == null || affectedUserId <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到封鎖效果目標玩家");
        }
        int currentTurn = currentTurnResolver.resolve(matchId);
        int expiresTurn = rawText.contains("次の相手の") ? currentTurn + 1 : currentTurn;
        Long targetHolomemId = null;
        boolean lockSpecificHolomem = rawText.contains("このホロメン")
            || rawText.contains("このカード")
            || rawText.contains("選んだホロメン")
            || rawText.contains("そのホロメン");
        if (lockSpecificHolomem) {
            targetHolomemId = effectTargetHolomemResolver.resolve(
                matchId,
                userId,
                targetType,
                targetHolomemCardInstanceId,
                true
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actions", actions);
        payload.put("zones", zones);
        payload.put("rawText", rawText);
        if (targetHolomemId != null && targetHolomemId > 0) {
            payload.put("targetHolomemId", targetHolomemId);
            payload.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
        }
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
            ) VALUES (?, ?, ?, ?, 'ACTION_LOCK', 1, ?, CAST(? AS jsonb))
            """,
            matchId,
            userId,
            affectedUserId,
            "DEBUFF",
            expiresTurn,
            effectTextParser.toJsonString(payload)
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted == 1);
        summary.put("statType", "ACTION_LOCK");
        summary.put("actions", actions);
        summary.put("zones", zones);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", targetHolomemId == null ? null : holomemCardInstanceResolver.resolve(targetHolomemId));
        summary.put("affectedUserId", affectedUserId);
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

    @FunctionalInterface
    interface OpponentUserResolver {
        Long resolve(Long matchId, Long userId);
    }

    @FunctionalInterface
    interface CurrentTurnResolver {
        int resolve(Long matchId);
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
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
