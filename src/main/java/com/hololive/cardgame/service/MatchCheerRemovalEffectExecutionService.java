package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchCheerRemovalEffectExecutionService {

    private static final Pattern CHEER_COUNT_PATTERN = Pattern.compile("エール\\s*(\\d+)\\s*枚");

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final TargetHolomemResolver targetHolomemResolver;
    private final HolomemOwnerResolver holomemOwnerResolver;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchCheerRemovalEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        TargetHolomemResolver targetHolomemResolver,
        HolomemOwnerResolver holomemOwnerResolver,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.targetHolomemResolver = targetHolomemResolver;
        this.holomemOwnerResolver = holomemOwnerResolver;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

    Map<String, Object> executeRemoveCheerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = targetHolomemResolver.resolve(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("REMOVE_CHEER 找不到目標 Holomen");
        }
        Long targetOwnerUserId = holomemOwnerResolver.resolve(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("REMOVE_CHEER 結算失敗：找不到目標擁有者");
        }

        int removeCount = Math.max(resolveCheerCount(effectNode, 1), 1);
        List<Map<String, Object>> cheerRows = jdbcTemplate.queryForList(
            """
            SELECT id, cheer_card_id, match_card_id
            FROM match_holomem_cheers
            WHERE match_holomem_id = ?
            ORDER BY id
            LIMIT ?
            """,
            targetHolomemId,
            removeCount
        );

        CheerRemovalResult result = removeCheerRows(matchId, targetOwnerUserId, cheerRows, targetHolomemId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
        summary.put("removeRequested", removeCount);
        summary.put("removeApplied", result.removedCheerCardIds().size());
        summary.put("removedCheerCardIds", result.removedCheerCardIds());
        summary.put("archivedCheerCardInstanceIds", result.archivedCardInstanceIds());
        return summary;
    }

    Map<String, Object> executeRemoveStageCheerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int removeCount = Math.max(resolveCheerCount(effectNode, 1), 1);
        List<Map<String, Object>> cheerRows = jdbcTemplate.queryForList(
            """
            SELECT hc.id, hc.cheer_card_id, hc.match_holomem_id, hc.match_card_id
            FROM match_holomem_cheers hc
            JOIN match_holomems h ON h.id = hc.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
            ORDER BY h.id, hc.id
            LIMIT ?
            """,
            matchId,
            userId,
            removeCount
        );

        CheerRemovalResult result = removeCheerRows(matchId, userId, cheerRows, null);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("removeRequested", removeCount);
        summary.put("removeApplied", result.removedCheerCardIds().size());
        summary.put("removedCheerCardIds", result.removedCheerCardIds());
        summary.put("sourceHolomemIds", result.sourceHolomemIds());
        summary.put("archivedCheerCardInstanceIds", result.archivedCardInstanceIds());
        return summary;
    }

    private CheerRemovalResult removeCheerRows(
        Long matchId,
        Long ownerUserId,
        List<Map<String, Object>> cheerRows,
        Long fallbackSourceHolomemId
    ) {
        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> removedCheerCardIds = new ArrayList<>();
        List<Long> sourceHolomemIds = new ArrayList<>();
        for (Map<String, Object> row : cheerRows) {
            Long cheerRowId = MatchEffectValueHelper.asLong(row.get("id"));
            String cheerCardId = asText(row.get("cheer_card_id"));
            Long sourceHolomemId = MatchEffectValueHelper.asLong(row.get("match_holomem_id"));
            Long cheerCardInstanceId = MatchEffectValueHelper.asLong(row.get("match_card_id"));
            if (cheerRowId == null || !StringUtils.hasText(cheerCardId)) {
                continue;
            }
            if (sourceHolomemId == null) {
                sourceHolomemId = fallbackSourceHolomemId == null ? resolveCheerSourceHolomemId(cheerRowId) : fallbackSourceHolomemId;
            }
            if (sourceHolomemId == null) {
                continue;
            }
            int deleted = jdbcTemplate.update(
                "DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?",
                cheerRowId,
                sourceHolomemId
            );
            if (deleted != 1) {
                continue;
            }
            removedCheerCardIds.add(cheerCardId);
            sourceHolomemIds.add(sourceHolomemId);
            Long archivedCardInstanceId = moveCheerCardInstanceToArchive(
                matchId,
                ownerUserId,
                cheerCardInstanceId,
                cheerCardId
            );
            if (archivedCardInstanceId != null) {
                archivedCardInstanceIds.add(archivedCardInstanceId);
            }
        }
        return new CheerRemovalResult(archivedCardInstanceIds, removedCheerCardIds, sourceHolomemIds);
    }

    private Long resolveCheerSourceHolomemId(Long cheerRowId) {
        return jdbcTemplate.query(
            "SELECT match_holomem_id FROM match_holomem_cheers WHERE id = ?",
            rs -> rs.next() ? rs.getLong("match_holomem_id") : null,
            cheerRowId
        );
    }

    private Long moveCheerCardInstanceToArchive(
        Long matchId,
        Long ownerUserId,
        Long cheerCardInstanceId,
        String cheerCardId
    ) {
        if ((cheerCardInstanceId == null || cheerCardInstanceId <= 0) && !StringUtils.hasText(cheerCardId)) {
            return null;
        }
        Long resolvedCardInstanceId = cheerCardInstanceId;
        if (resolvedCardInstanceId == null || resolvedCardInstanceId <= 0) {
            resolvedCardInstanceId = jdbcTemplate.query(
                """
                SELECT id
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'STAGE'
                  AND card_id = ?
                ORDER BY id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                ownerUserId,
                cheerCardId
            );
        }
        if (resolvedCardInstanceId == null) {
            return null;
        }
        int archiveOrder = nextZoneOrder(matchId, ownerUserId, "ARCHIVE");
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = ?,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'STAGE'
            """,
            archiveOrder,
            resolvedCardInstanceId,
            matchId,
            ownerUserId
        );
        return updated == 1 ? resolvedCardInstanceId : null;
    }

    private int nextZoneOrder(Long matchId, Long userId, String zone) {
        Integer next = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        return next == null ? 1 : next;
    }

    private int resolveCheerCount(JsonNode effectNode, int defaultValue) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        int byText = effectTextParser.extractByPattern(
            effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect")),
            CHEER_COUNT_PATTERN
        );
        return byText > 0 ? byText : defaultValue;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record CheerRemovalResult(
        List<Long> archivedCardInstanceIds,
        List<String> removedCheerCardIds,
        List<Long> sourceHolomemIds
    ) {
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
    interface HolomemOwnerResolver {
        Long resolve(Long matchId, Long holomemId);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
