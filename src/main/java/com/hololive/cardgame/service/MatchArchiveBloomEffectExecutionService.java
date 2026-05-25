package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchArchiveBloomEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchEffectSearchService searchService;

    MatchArchiveBloomEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        SearchCriteriaParser searchCriteriaParser,
        MatchEffectSearchService searchService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.searchCriteriaParser = searchCriteriaParser;
        this.searchService = searchService;
    }

    /**
     * 執行從 Archive Bloom 的特殊效果，並保留疊卡繼承資料。
     */
    Map<String, Object> executeBloomFromArchiveEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int currentTurn = resolveCurrentTurnNumber(matchId);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        String requiredLevel = StringUtils.hasText(criteria.levelType()) ? criteria.levelType() : "DEBUT";

        List<Map<String, Object>> targetCandidates = jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   h.current_level,
                   h.damage_taken,
                   h.last_bloom_turn,
                   h.is_rested,
                   'MEMBER' AS card_type,
                   h.current_level AS level_type,
                   c.name,
                   c.tags_json::text AS tags_json,
                   m.main_color,
                   m.sub_color,
                   GREATEST(COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0), 0) AS remain_hp
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            LEFT JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER','COLLAB','BACK')
              AND (? = '' OR h.current_level = ?)
              AND (? = '' OR c.name ILIKE '%' || ? || '%')
              AND (
                    ? = ''
                    OR EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(COALESCE(c.tags_json, '[]'::jsonb)) AS t(tag)
                        WHERE t.tag = ?
                    )
                  )
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END,
                     h.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("holomem_id", rs.getLong("holomem_id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("current_level", rs.getString("current_level"));
                row.put("damage_taken", rs.getInt("damage_taken"));
                row.put("last_bloom_turn", rs.getObject("last_bloom_turn"));
                row.put("is_rested", rs.getObject("is_rested"));
                row.put("card_type", rs.getString("card_type"));
                row.put("level_type", rs.getString("level_type"));
                row.put("name", rs.getString("name"));
                row.put("tags_json", rs.getString("tags_json"));
                row.put("main_color", rs.getString("main_color"));
                row.put("sub_color", rs.getString("sub_color"));
                row.put("remain_hp", rs.getObject("remain_hp"));
                return row;
            },
            matchId,
            userId,
            nullToEmpty(requiredLevel),
            nullToEmpty(requiredLevel),
            nullToEmpty(criteria.nameContains()),
            nullToEmpty(criteria.nameContains()),
            nullToEmpty(criteria.tag()),
            nullToEmpty(criteria.tag())
        );
        Map<String, Object> target = targetCandidates.stream()
            .filter(row -> {
                Object lastBloomTurn = row.get("last_bloom_turn");
                if (lastBloomTurn instanceof Number number) {
                    return number.intValue() != currentTurn;
                }
                return true;
            })
            .filter(row -> searchService.matchesSearchCriteria(row, criteria))
            .findFirst()
            .orElse(null);
        if (target == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有可從 Archive 進行 Bloom 的目標");
        }

        Long targetHolomemId = MatchEffectValueHelper.asLong(target.get("holomem_id"));
        String targetName = MatchEffectValueHelper.asText(target.get("name"));
        String targetLevel = MatchEffectValueHelper.asText(target.get("current_level"));
        int targetDamageTaken = MatchEffectValueHelper.asInt(target.get("damage_taken"));
        int targetRank = resolveBloomLevelRank(targetLevel);
        if (targetHolomemId == null || !StringUtils.hasText(targetName) || targetRank < 0) {
            return executeNoOpEffect(effectType, effectNode, "目標 Holomem 資料不足，無法執行 Archive Bloom");
        }

        Map<String, Object> archiveBloomCard = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   m.level_type,
                   m.hp,
                   m.bloom_level
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'ARCHIVE'
              AND c.name = ?
              AND m.bloom_level > ?
              AND m.hp >= ?
            ORDER BY m.bloom_level, mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("card_instance_id", rs.getLong("card_instance_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("level_type", rs.getString("level_type"));
                row.put("hp", rs.getInt("hp"));
                row.put("bloom_level", rs.getInt("bloom_level"));
                return row;
            },
            matchId,
            userId,
            targetName,
            targetRank,
            targetDamageTaken
        );
        if (archiveBloomCard == null) {
            return executeNoOpEffect(effectType, effectNode, "Archive 中找不到可用的 Bloom 卡");
        }

        Long bloomCardInstanceId = MatchEffectValueHelper.asLong(archiveBloomCard.get("card_instance_id"));
        String bloomCardId = MatchEffectValueHelper.asText(archiveBloomCard.get("card_id"));
        String bloomLevelType = MatchEffectValueHelper.asText(archiveBloomCard.get("level_type"));
        if (bloomCardInstanceId == null || !StringUtils.hasText(bloomCardId)) {
            return executeNoOpEffect(effectType, effectNode, "Archive Bloom 卡資料不完整");
        }

        int moved = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            bloomCardInstanceId,
            matchId,
            userId
        );
        if (moved != 1) {
            return executeNoOpEffect(effectType, effectNode, "Archive Bloom 移動卡片失敗");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET match_card_id = ?,
                card_id = ?,
                current_level = ?,
                last_bloom_turn = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            bloomCardInstanceId,
            bloomCardId,
            normalizeHolomemLevel(bloomLevelType),
            currentTurn,
            targetHolomemId,
            matchId,
            userId
        );
        recordHolomemStackCard(targetHolomemId, bloomCardInstanceId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("bloomCardInstanceId", bloomCardInstanceId);
        summary.put("bloomCardId", bloomCardId);
        summary.put("bloomLevelType", normalizeHolomemLevel(bloomLevelType));
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

    private String nullToEmpty(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    private int resolveCurrentTurnNumber(Long matchId) {
        Integer turn = jdbcTemplate.query(
            "SELECT turn_number FROM matches WHERE id = ?",
            rs -> rs.next() ? rs.getInt("turn_number") : null,
            matchId
        );
        if (turn == null || turn <= 0) {
            return 1;
        }
        return turn;
    }

    private void recordHolomemStackCard(Long matchHolomemId, Long matchCardId) {
        if (matchHolomemId == null || matchCardId == null) {
            return;
        }
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(stack_order), 0) + 1
            FROM match_holomem_stack_cards
            WHERE match_holomem_id = ?
            """,
            Integer.class,
            matchHolomemId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, ?)
            ON CONFLICT (match_card_id) DO NOTHING
            """,
            matchHolomemId,
            matchCardId,
            nextOrder == null ? 1 : nextOrder
        );
    }

    private Long resolveHolomemCardInstanceId(Long matchHolomemId) {
        if (matchHolomemId == null) {
            return null;
        }
        return jdbcTemplate.query(
            "SELECT match_card_id FROM match_holomems WHERE id = ?",
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchHolomemId
        );
    }

    private String normalizeHolomemLevel(String levelType) {
        String normalized = normalizeLevelType(levelType);
        if ("FIRST".equals(normalized) || "SECOND".equals(normalized) || "SPOT".equals(normalized) || "BUZZ".equals(normalized)) {
            return normalized;
        }
        return "DEBUT";
    }

    private String normalizeLevelType(String levelType) {
        String normalized = MatchEffectValueHelper.normalize(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }

    private int resolveBloomLevelRank(String levelType) {
        String normalized = normalizeHolomemLevel(levelType);
        return switch (normalized) {
            case "DEBUT" -> 0;
            case "FIRST" -> 1;
            case "SECOND" -> 2;
            case "BUZZ" -> 3;
            default -> -1;
        };
    }
}
