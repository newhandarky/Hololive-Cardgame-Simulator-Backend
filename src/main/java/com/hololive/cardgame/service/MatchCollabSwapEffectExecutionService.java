package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchCollabSwapEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final TargetHolomemResolver targetHolomemResolver;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchCollabSwapEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        TargetHolomemResolver targetHolomemResolver,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.targetHolomemResolver = targetHolomemResolver;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

    /**
     * 執行與 Collab 位互換位置效果。
     */
    Map<String, Object> executeSwapWithCollabEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long selfHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        Long sourceHolomemId = targetHolomemResolver.resolve(matchId, userId, selfHolomemCardInstanceId);
        if (sourceHolomemId == null) {
            sourceHolomemId = jdbcTemplate.query(
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
                userId
            );
        }
        if (sourceHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可交換的來源 Holomem");
        }

        Map<String, Object> source = jdbcTemplate.query(
            """
            SELECT h.id, h.zone, COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0) AS remain_hp
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.id = ?
              AND h.match_id = ?
              AND h.owner_user_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("zone", rs.getString("zone"));
                row.put("remain_hp", rs.getInt("remain_hp"));
                return row;
            },
            sourceHolomemId,
            matchId,
            userId
        );
        if (source == null) {
            return executeNoOpEffect(effectType, effectNode, "來源 Holomem 不存在");
        }
        String sourceZone = normalize(source.get("zone"));
        if (rawText.contains("バックポジション限定") && !"BACK".equals(sourceZone)) {
            return executeNoOpEffect(effectType, effectNode, "來源 Holomem 不在 BACK，無法交換");
        }

        boolean requireLowHpCollab = rawText.contains("残りHP70以下");
        Map<String, Object> collabTarget = jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0) AS remain_hp
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'COLLAB'
              AND (? = FALSE OR (COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0)) <= 70)
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("remain_hp", rs.getInt("remain_hp"));
                return row;
            },
            matchId,
            userId,
            requireLowHpCollab
        );
        if (collabTarget == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有符合條件的 COLLAB 目標可交換");
        }
        Long collabHolomemId = asLong(collabTarget.get("id"));
        if (collabHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "COLLAB 目標資料不足");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'COLLAB',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            sourceHolomemId,
            matchId,
            userId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'BACK',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            collabHolomemId,
            matchId,
            userId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("swapped", true);
        summary.put("sourceHolomemId", sourceHolomemId);
        summary.put("targetHolomemId", collabHolomemId);
        summary.put("sourceHolomemCardInstanceId", holomemCardInstanceResolver.resolve(sourceHolomemId));
        summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(collabHolomemId));
        summary.put("requireLowHpCollab", requireLowHpCollab);
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

    private String normalize(Object value) {
        return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @FunctionalInterface
    interface TargetHolomemResolver {
        Long resolve(Long matchId, Long userId, Long targetHolomemCardInstanceId);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
