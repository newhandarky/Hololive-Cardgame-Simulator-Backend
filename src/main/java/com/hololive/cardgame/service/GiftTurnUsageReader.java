package com.hololive.cardgame.service;

import org.springframework.jdbc.core.JdbcTemplate;

class GiftTurnUsageReader {

    private final JdbcTemplate jdbcTemplate;

    GiftTurnUsageReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    boolean isGiftAlreadyUsedThisTurn(Long matchId, Long userId, int turnNumber, Long holderHolomemId) {
        if (matchId == null || userId == null || turnNumber <= 0 || holderHolomemId == null || holderHolomemId <= 0) {
            return false;
        }
        Integer used = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'GIFT_TRIGGER'
              AND payload ->> 'giftHolderHolomemId' = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            turnNumber,
            holderHolomemId.toString()
        );
        return used != null && used > 0;
    }
}
