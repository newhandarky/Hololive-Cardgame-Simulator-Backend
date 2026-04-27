package com.hololive.cardgame.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PlayCardActionResolver {

    private final JdbcTemplate jdbcTemplate;

    public PlayCardActionResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PlayCardResolutionResult resolve(
        PlayCardAction action,
        PlayCardValidationContext context
    ) {
        if (action == null || context == null || context.match() == null || context.sourceCard() == null) {
            throw new IllegalArgumentException("PLAY_CARD 結算缺少必要上下文");
        }

        PlayCardSourceCardSnapshot sourceCard = context.sourceCard();
        String currentLevel = normalizeLevel(sourceCard.levelType());
        moveCardToStage(action);
        Long matchHolomemId = insertMatchHolomem(action, sourceCard, currentLevel, context.currentTurnNumber());
        if (matchHolomemId == null) {
            throw new IllegalStateException("建立場上 Holomem 失敗");
        }
        insertStackCard(matchHolomemId, action.cardInstanceId());

        return new PlayCardResolutionResult(
            context.match(),
            action.actorUserId(),
            context.currentTurnNumber(),
            action.cardInstanceId(),
            sourceCard.cardId(),
            sourceCard.zone(),
            normalize(action.targetZone()),
            matchHolomemId,
            context.currentTurnNumber(),
            action.openingReset(),
            currentLevel,
            action.openingReset()
        );
    }

    private void moveCardToStage(PlayCardAction action) {
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            action.openingReset(),
            action.cardInstanceId(),
            action.matchId(),
            action.actorUserId()
        );
        if (updated != 1) {
            throw new IllegalStateException("放置 Holomem 失敗，請重新整理對戰狀態");
        }
    }

    private Long insertMatchHolomem(
        PlayCardAction action,
        PlayCardSourceCardSnapshot sourceCard,
        String currentLevel,
        int turnNumber
    ) {
        return jdbcTemplate.query(
            """
            INSERT INTO match_holomems (
                match_id,
                owner_user_id,
                match_card_id,
                card_id,
                zone,
                is_rested,
                is_face_down,
                damage_taken,
                current_level,
                entered_turn_number
            ) VALUES (?, ?, ?, ?, ?, FALSE, ?, 0, ?, ?)
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            action.matchId(),
            action.actorUserId(),
            action.cardInstanceId(),
            sourceCard.cardId(),
            normalize(action.targetZone()),
            action.openingReset(),
            currentLevel,
            turnNumber
        );
    }

    private void insertStackCard(Long matchHolomemId, Long cardInstanceId) {
        int updated = jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, 1)
            """,
            matchHolomemId,
            cardInstanceId
        );
        if (updated != 1) {
            throw new IllegalStateException("建立 Holomem stack 關聯失敗");
        }
    }

    private String normalizeLevel(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "DEBUT" : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
