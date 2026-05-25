package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchRevealToArchiveEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver;
    private final MatchEffectSearchService searchService;

    MatchRevealToArchiveEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        SearchCriteriaParser searchCriteriaParser,
        MatchCardSelectionRequestResolver cardSelectionRequestResolver,
        MatchEffectSearchService searchService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.searchCriteriaParser = searchCriteriaParser;
        this.cardSelectionRequestResolver = cardSelectionRequestResolver;
        this.searchService = searchService;
    }

    /**
     * 執行展示後歸檔效果（Reveal -> Archive）。
     */
    Map<String, Object> executeRevealToArchiveEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "アーカイブ", 1);
        int archiveCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        List<Map<String, Object>> candidates = searchService.loadCandidatesFromZone(
            matchId,
            userId,
            "DECK",
            criteria,
            false
        );
        List<Map<String, Object>> selected = candidates.subList(0, Math.min(archiveCount, candidates.size()));

        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> archivedCardIds = new ArrayList<>();
        int nextArchiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = MatchEffectValueHelper.asLong(row.get("id"));
            String cardId = MatchEffectValueHelper.asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
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
                  AND zone = 'DECK'
                """,
                nextArchiveOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            archivedCardInstanceIds.add(cardInstanceId);
            archivedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("archiveRequested", archiveCount);
        summary.put("candidateCount", candidates.size());
        summary.put("archiveApplied", archivedCardInstanceIds.size());
        summary.put("archivedCardInstanceIds", archivedCardInstanceIds);
        summary.put("archivedCardIds", archivedCardIds);
        summary.put("criteria", searchService.buildCriteriaSummary(criteria));
        return summary;
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
