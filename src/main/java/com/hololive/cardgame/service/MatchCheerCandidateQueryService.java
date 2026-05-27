package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.SearchCriteria;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class MatchCheerCandidateQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final MatchEffectSearchService searchService;

    MatchCheerCandidateQueryService(JdbcTemplate jdbcTemplate, MatchEffectSearchService searchService) {
        this.jdbcTemplate = jdbcTemplate;
        this.searchService = searchService;
    }

    Map<String, Object> findCheerCardFromZone(Long matchId, Long userId, String zone) {
        return findCheerCardFromZone(matchId, userId, zone, SearchCriteria.empty());
    }

    Map<String, Object> findCheerCardFromZone(Long matchId, Long userId, String zone, SearchCriteria criteria) {
        String normalizedZone = normalize(zone);
        if (!"CHEER_DECK".equals(normalizedZone) && !"ARCHIVE".equals(normalizedZone) && !"STAGE".equals(normalizedZone)) {
            return null;
        }
        List<Map<String, Object>> candidates = searchService.loadCandidatesFromZone(
            matchId,
            userId,
            normalizedZone,
            criteria,
            false
        );
        if (candidates.isEmpty()) {
            return null;
        }
        Map<String, Object> candidate = candidates.get(0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", candidate.get("id"));
        row.put("card_id", candidate.get("card_id"));
        row.put("zone", normalizedZone);
        return row;
    }

    Map<String, Object> findAttachableCheerCard(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, mc.zone
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone IN ('CHEER_DECK','ARCHIVE','HAND')
            ORDER BY CASE mc.zone WHEN 'CHEER_DECK' THEN 1 WHEN 'ARCHIVE' THEN 2 WHEN 'HAND' THEN 3 ELSE 9 END,
                     mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                return row;
            },
            matchId,
            userId
        );
    }

    private String normalize(Object value) {
        return MatchEffectValueHelper.normalize(value);
    }
}
