package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

class MatchTurnStartCollabReturnService {

    private final JdbcTemplate jdbcTemplate;

    MatchTurnStartCollabReturnService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void returnCollabToBackAsRested(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return;
        }
        List<Map<String, Object>> collabRows = jdbcTemplate.queryForList(
            """
            SELECT id, card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'COLLAB'
            """,
            matchId,
            userId
        );
        if (collabRows.isEmpty()) {
            return;
        }
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'BACK',
                is_rested = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'COLLAB'
            """,
            matchId,
            userId
        );
        boolean shouldKeepHbp03039Unrested = isOwnCenterHolomemNameContains(matchId, userId, "フワワ・アビスガード");
        if (!shouldKeepHbp03039Unrested) {
            return;
        }
        List<Long> movedCollabIds = collabRows.stream()
            .map(row -> MatchEffectValueHelper.asLong(row.get("id")))
            .filter(Objects::nonNull)
            .toList();
        if (movedCollabIds.isEmpty()) {
            return;
        }
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
              AND card_id = 'HBP03-039'
              AND id = ANY (?::bigint[])
            """,
            ps -> {
                ps.setLong(1, matchId);
                ps.setLong(2, userId);
                ps.setArray(3, ps.getConnection().createArrayOf("bigint", movedCollabIds.toArray()));
            }
        );
    }

    private boolean isOwnCenterHolomemNameContains(Long matchId, Long userId, String requiredNamePart) {
        if (matchId == null || userId == null || !StringUtils.hasText(requiredNamePart)) {
            return false;
        }
        String centerName = jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'CENTER'
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("name") : null,
            matchId,
            userId
        );
        return StringUtils.hasText(centerName) && centerName.contains(requiredNamePart);
    }
}
