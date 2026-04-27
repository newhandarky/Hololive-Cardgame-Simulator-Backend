package com.hololive.cardgame.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CollabActionResolver {

    private final JdbcTemplate jdbcTemplate;

    public CollabActionResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CollabResolutionResult resolve(CollabAction action, CollabValidationContext context) {
        if (action == null || context == null || context.match() == null || context.sourceHolomem() == null) {
            throw new IllegalArgumentException("COLLAB 結算缺少必要上下文");
        }
        CollabSourceHolomemSnapshot sourceHolomem = context.sourceHolomem();

        moveSourceHolomemToCollab(action);
        Long holopowerCardInstanceId = moveTopDeckCardToHolopower(action.matchId(), action.actorUserId());

        return new CollabResolutionResult(
            context.match(),
            action.actorUserId(),
            context.currentTurnNumber(),
            sourceHolomem.holomemId(),
            sourceHolomem.cardInstanceId(),
            sourceHolomem.cardId(),
            sourceHolomem.zone(),
            action.targetZone(),
            holopowerCardInstanceId
        );
    }

    private void moveSourceHolomemToCollab(CollabAction action) {
        int moved = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'COLLAB',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
              AND zone = 'BACK'
            """,
            action.matchId(),
            action.actorUserId(),
            action.sourceCardInstanceId()
        );
        if (moved != 1) {
            throw new IllegalStateException("移動 Holomem 失敗，請重新整理");
        }
    }

    private Long moveTopDeckCardToHolopower(Long matchId, Long userId) {
        Long deckCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (deckCardInstanceId == null) {
            return null;
        }
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HOLOPOWER'
            """,
            Integer.class,
            matchId,
            userId
        );
        int moved = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'HOLOPOWER',
                order_index = ?,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            nextOrder == null ? 1 : nextOrder,
            deckCardInstanceId,
            matchId,
            userId
        );
        return moved == 1 ? deckCardInstanceId : null;
    }
}
