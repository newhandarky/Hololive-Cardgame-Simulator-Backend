package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchDiscardHandEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;
    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver;
    private final MatchEffectSearchService searchService;

    MatchDiscardHandEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        EffectTextParser effectTextParser,
        SearchCriteriaParser searchCriteriaParser,
        MatchCardSelectionRequestResolver cardSelectionRequestResolver,
        MatchEffectSearchService searchService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.effectTextParser = effectTextParser;
        this.searchCriteriaParser = searchCriteriaParser;
        this.cardSelectionRequestResolver = cardSelectionRequestResolver;
        this.searchService = searchService;
    }

    /**
     * 執行棄手牌效果，支援指定條件與自動挑選。
     */
    Map<String, Object> executeDiscardHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        String discardClause = extractCostClause(rawText);
        SearchCriteria discardCriteria = resolveSearchCriteriaFromRawText(discardClause);
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "手札", 1);
        int discardCount = Math.max(requestedCount, 1);

        List<Map<String, Object>> handCards;
        if (discardCriteria.isEmpty()) {
            handCards = jdbcTemplate.query(
                """
                SELECT id, card_id
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'HAND'
                ORDER BY order_index NULLS LAST, id
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("card_id", rs.getString("card_id"));
                    return row;
                },
                matchId,
                userId,
                discardCount
            );
        } else {
            handCards = new ArrayList<>(searchService.loadCandidatesFromZone(matchId, userId, "HAND", discardCriteria, false));
            if (handCards.size() > discardCount) {
                handCards = new ArrayList<>(handCards.subList(0, discardCount));
            }
        }

        List<Long> discardedCardInstanceIds = new ArrayList<>();
        List<String> discardedCardIds = new ArrayList<>();
        int nextArchiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
        for (Map<String, Object> row : handCards) {
            Long cardInstanceId = MatchEffectValueHelper.asLong(row.get("id"));
            String cardId = MatchEffectValueHelper.asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'ARCHIVE',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'HAND'
                """,
                nextArchiveOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                continue;
            }
            discardedCardInstanceIds.add(cardInstanceId);
            discardedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("discardRequested", discardCount);
        summary.put("discardApplied", discardedCardInstanceIds.size());
        if (!discardCriteria.isEmpty()) {
            summary.put("discardCriteria", searchService.buildCriteriaSummary(discardCriteria));
        }
        summary.put("discardedCardInstanceIds", discardedCardInstanceIds);
        summary.put("discardedCardIds", discardedCardIds);
        return summary;
    }

    private SearchCriteria resolveSearchCriteriaFromRawText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return SearchCriteria.empty();
        }
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

    private String extractCostClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int splitIndex = findClauseSeparator(rawText);
        return splitIndex < 0 ? rawText : rawText.substring(0, splitIndex).trim();
    }

    private int findClauseSeparator(String rawText) {
        int fullWidthIndex = rawText.indexOf('：');
        int halfWidthIndex = rawText.indexOf(':');
        if (fullWidthIndex < 0) {
            return halfWidthIndex;
        }
        if (halfWidthIndex < 0) {
            return fullWidthIndex;
        }
        return Math.min(fullWidthIndex, halfWidthIndex);
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
}
