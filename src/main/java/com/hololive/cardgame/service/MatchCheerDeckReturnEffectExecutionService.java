package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchCheerDeckReturnEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver;

    MatchCheerDeckReturnEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        MatchCardSelectionRequestResolver cardSelectionRequestResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.cardSelectionRequestResolver = cardSelectionRequestResolver;
    }

    /**
     * 將 Archive 或 Stage 附屬 cheer 返回 Cheer Deck 底。
     */
    Map<String, Object> executeReturnCheerToDeckBottomEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        String colorFilter = resolveCheerColorFilter(rawText);
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "エールデッキの下に戻", 1);
        int returnCount = Math.max(requestedCount, 1);
        boolean fromStageAttachedCheer = rawText.contains("ステージのエール");

        List<Map<String, Object>> candidates = fromStageAttachedCheer
            ? loadStageAttachedCheerCandidates(matchId, userId, colorFilter, returnCount)
            : loadArchiveCheerCandidates(matchId, userId, colorFilter, returnCount);

        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        int nextCheerDeckOrder = nextZoneOrder(matchId, userId, "CHEER_DECK");
        for (Map<String, Object> row : candidates) {
            String cardId = MatchEffectValueHelper.asText(row.get("card_id"));
            if (!StringUtils.hasText(cardId)) {
                continue;
            }
            Long cardInstanceId = fromStageAttachedCheer
                ? resolveStageAttachedCheerCardInstanceId(matchId, userId, row, cardId)
                : MatchEffectValueHelper.asLong(row.get("id"));
            if (cardInstanceId == null || cardInstanceId <= 0) {
                continue;
            }
            String sourceZone = fromStageAttachedCheer ? "STAGE" : "ARCHIVE";
            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'CHEER_DECK',
                    order_index = ?,
                    is_face_down = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = ?
                """,
                nextCheerDeckOrder++,
                cardInstanceId,
                matchId,
                userId,
                sourceZone
            );
            if (moved != 1) {
                continue;
            }
            if (fromStageAttachedCheer) {
                deleteStageAttachedCheerRow(row);
            }
            movedCardInstanceIds.add(cardInstanceId);
            movedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("sourceZone", fromStageAttachedCheer ? "STAGE" : "ARCHIVE");
        summary.put("returnRequested", returnCount);
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("colorFilter", colorFilter);
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        return summary;
    }

    private List<Map<String, Object>> loadStageAttachedCheerCandidates(
        Long matchId,
        Long userId,
        String colorFilter,
        int returnCount
    ) {
        return jdbcTemplate.query(
            """
            SELECT hc.id AS cheer_row_id,
                   hc.match_card_id,
                   hc.cheer_card_id AS card_id,
                   cc.color
            FROM match_holomem_cheers hc
            JOIN match_holomems h ON h.id = hc.match_holomem_id
            JOIN cheer_cards cc ON cc.card_id = hc.cheer_card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND (? = '' OR cc.color = ?)
            ORDER BY hc.id
            LIMIT ?
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cheer_row_id", rs.getLong("cheer_row_id"));
                long matchCardId = rs.getLong("match_card_id");
                row.put("match_card_id", rs.wasNull() ? null : matchCardId);
                row.put("card_id", rs.getString("card_id"));
                row.put("color", rs.getString("color"));
                return row;
            },
            matchId,
            userId,
            nullToEmpty(colorFilter),
            nullToEmpty(colorFilter),
            returnCount
        );
    }

    private List<Map<String, Object>> loadArchiveCheerCandidates(
        Long matchId,
        Long userId,
        String colorFilter,
        int returnCount
    ) {
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, cc.color
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'ARCHIVE'
              AND (? = '' OR cc.color = ?)
            ORDER BY mc.order_index NULLS LAST, mc.id
            LIMIT ?
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("color", rs.getString("color"));
                return row;
            },
            matchId,
            userId,
            nullToEmpty(colorFilter),
            nullToEmpty(colorFilter),
            returnCount
        );
    }

    private Long resolveStageAttachedCheerCardInstanceId(
        Long matchId,
        Long userId,
        Map<String, Object> row,
        String cardId
    ) {
        Long cardInstanceId = MatchEffectValueHelper.asLong(row.get("match_card_id"));
        if (cardInstanceId != null && cardInstanceId > 0) {
            return cardInstanceId;
        }
        return jdbcTemplate.query(
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
            userId,
            cardId
        );
    }

    private void deleteStageAttachedCheerRow(Map<String, Object> row) {
        Long cheerRowId = MatchEffectValueHelper.asLong(row.get("cheer_row_id"));
        if (cheerRowId == null || cheerRowId <= 0) {
            return;
        }
        jdbcTemplate.update(
            """
            DELETE FROM match_holomem_cheers
            WHERE id = ?
            """,
            cheerRowId
        );
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

    private String resolveCheerColorFilter(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        if (rawText.contains("赤")) {
            return "RED";
        }
        if (rawText.contains("青")) {
            return "BLUE";
        }
        if (rawText.contains("緑")) {
            return "GREEN";
        }
        if (rawText.contains("白")) {
            return "WHITE";
        }
        if (rawText.contains("紫")) {
            return "PURPLE";
        }
        if (rawText.contains("黄")) {
            return "YELLOW";
        }
        if (rawText.contains("無色")) {
            return "COLORLESS";
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
