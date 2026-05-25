package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchHealEffectExecutionService {

    private static final Pattern HEAL_PATTERN = Pattern.compile("HP\\s*(\\d+)\\s*回復");

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final EffectTargetHolomemResolver effectTargetHolomemResolver;
    private final HolomemOwnerResolver holomemOwnerResolver;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;
    private final HpChangeBlocker hpChangeBlocker;

    MatchHealEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        EffectTargetHolomemResolver effectTargetHolomemResolver,
        HolomemOwnerResolver holomemOwnerResolver,
        HolomemCardInstanceResolver holomemCardInstanceResolver,
        HpChangeBlocker hpChangeBlocker
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.effectTargetHolomemResolver = effectTargetHolomemResolver;
        this.holomemOwnerResolver = holomemOwnerResolver;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
        this.hpChangeBlocker = hpChangeBlocker;
    }

    /**
     * 執行回復效果，將目標傷害值下修至不低於 0。
     */
    Map<String, Object> executeHealEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = effectTargetHolomemResolver.resolve(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            false
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("HEAL 找不到可回復的 Holomen");
        }
        Long targetOwnerUserId = holomemOwnerResolver.resolve(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("HEAL 結算失敗：找不到目標擁有者");
        }

        int healRequested = resolveHealValue(effectNode);
        if (healRequested <= 0) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("healRequested", 0);
            summary.put("healApplied", 0);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("reason", "無可用回復數值");
            return summary;
        }
        if (hpChangeBlocker.isBlocked(matchId, userId, targetOwnerUserId, targetHolomemId, effectType)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
            summary.put("healRequested", healRequested);
            summary.put("healApplied", 0);
            summary.put("reason", "目標在相手のメインステップ中不受相手能力的 HP 變動影響");
            return summary;
        }

        Integer beforeDamage = jdbcTemplate.query(
            """
            SELECT damage_taken
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            rs -> rs.next() ? rs.getInt("damage_taken") : null,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );
        if (beforeDamage == null) {
            throw new IllegalStateException("HEAL 結算失敗：找不到目標 Holomen");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = GREATEST(COALESCE(damage_taken, 0) - ?, 0),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            healRequested,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );

        int afterDamage = jdbcTemplate.query(
            "SELECT COALESCE(damage_taken, 0) FROM match_holomems WHERE id = ?",
            rs -> rs.next() ? rs.getInt(1) : 0,
            targetHolomemId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
        summary.put("healRequested", healRequested);
        summary.put("healApplied", Math.max(beforeDamage - afterDamage, 0));
        summary.put("damageBefore", beforeDamage);
        summary.put("damageAfter", afterDamage);
        return summary;
    }

    private int resolveHealValue(JsonNode effectNode) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "amount", "heal");
        if (fromFields > 0) {
            return fromFields;
        }
        return effectTextParser.extractByPattern(
            effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect")),
            HEAL_PATTERN
        );
    }

    @FunctionalInterface
    interface EffectTargetHolomemResolver {
        Long resolve(
            Long matchId,
            Long userId,
            String targetType,
            Long requestedTargetCardInstanceId,
            boolean defaultOpponent
        );
    }

    @FunctionalInterface
    interface HolomemOwnerResolver {
        Long resolve(Long matchId, Long holomemId);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long holomemId);
    }

    @FunctionalInterface
    interface HpChangeBlocker {
        boolean isBlocked(Long matchId, Long sourceUserId, Long targetOwnerUserId, Long targetHolomemId, String effectType);
    }
}
