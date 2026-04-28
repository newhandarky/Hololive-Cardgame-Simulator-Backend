package com.hololive.cardgame.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

class AttackPerformanceAvailabilityService {

    private final JdbcTemplate jdbcTemplate;

    AttackPerformanceAvailabilityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    int countArtUsedByZoneThisTurn(Long matchId, Long userId, int turnNumber, String zone) {
        String normalizedZone = normalizeZone(zone);
        Integer used = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions ma
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.turn_number = ?
              AND ma.action_type = 'ATTACK_ART'
              AND UPPER(COALESCE(ma.payload ->> 'attackerZone', '')) = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            turnNumber,
            normalizedZone
        );
        return used == null ? 0 : used;
    }

    boolean hasAvailableArtAttacker(Long matchId, Long userId, int turnNumber) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT zone, is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB')
            """,
            matchId,
            userId
        );
        for (Map<String, Object> row : rows) {
            String zone = normalizeZone(row.get("zone"));
            boolean rested = toBoolean(row.get("is_rested"));
            if (!rested && countArtUsedByZoneThisTurn(matchId, userId, turnNumber, zone) == 0) {
                return true;
            }
        }
        return false;
    }

    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString().trim();
        return StringUtils.hasText(text) ? text.toUpperCase(Locale.ROOT) : "";
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
