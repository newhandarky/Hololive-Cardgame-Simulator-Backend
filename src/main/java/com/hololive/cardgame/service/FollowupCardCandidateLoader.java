package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

class FollowupCardCandidateLoader {

    private final JdbcTemplate jdbcTemplate;

    FollowupCardCandidateLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Map<String, Object> loadOwnedCardCandidateForDecision(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String fallbackZone,
        String fallbackCardId
    ) {
        return loadCardCandidateForDecision(
            matchId,
            userId,
            userId,
            cardInstanceId,
            fallbackZone,
            fallbackCardId
        );
    }

    Map<String, Object> loadCardCandidateForDecision(
        Long matchId,
        Long viewerUserId,
        Long ownerUserId,
        Long cardInstanceId,
        String fallbackZone,
        String fallbackCardId
    ) {
        Map<String, Object> row = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   mc.zone,
                   c.name,
                   c.card_type,
                   c.image_url,
                   m.level_type
            FROM match_cards mc
            LEFT JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("cardInstanceId", rs.getLong("card_instance_id"));
                value.put("cardId", rs.getString("card_id"));
                value.put("zone", normalize(rs.getString("zone")));
                value.put("name", rs.getString("name"));
                value.put("cardType", rs.getString("card_type"));
                value.put("imageUrl", rs.getString("image_url"));
                value.put("levelType", rs.getString("level_type"));
                return value;
            },
            matchId,
            ownerUserId,
            cardInstanceId
        );
        if (row != null) {
            if (!Objects.equals(viewerUserId, ownerUserId)) {
                row.put("zone", null);
            }
            return row;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("cardInstanceId", cardInstanceId);
        fallback.put("cardId", fallbackCardId);
        fallback.put("zone", normalize(fallbackZone));
        fallback.put("name", null);
        fallback.put("cardType", null);
        fallback.put("imageUrl", null);
        fallback.put("levelType", null);
        return fallback;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
