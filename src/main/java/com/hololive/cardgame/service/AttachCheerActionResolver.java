package com.hololive.cardgame.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AttachCheerActionResolver {

    private final JdbcTemplate jdbcTemplate;

    public AttachCheerActionResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AttachCheerResolutionResult resolve(
        AttachCheerAction action,
        AttachCheerValidationContext context
    ) {
        if (
            action == null ||
                context == null ||
                context.match() == null ||
                context.sourceCard() == null ||
                context.targetHolomem() == null
        ) {
            throw new IllegalArgumentException("ATTACH_CHEER 結算缺少必要上下文");
        }

        AttachCheerSourceCardSnapshot sourceCard = context.sourceCard();
        AttachCheerTargetHolomemSnapshot targetHolomem = context.targetHolomem();

        moveCheerCardToStage(action);
        Long attachmentId = insertAttachment(targetHolomem.holomemId(), action.cheerCardInstanceId(), sourceCard.cardId());

        return new AttachCheerResolutionResult(
            context.match(),
            action.actorUserId(),
            context.currentTurnNumber(),
            action.cheerCardInstanceId(),
            sourceCard.cardId(),
            sourceCard.zone(),
            targetHolomem.holomemId(),
            targetHolomem.cardInstanceId(),
            attachmentId
        );
    }

    private void moveCheerCardToStage(AttachCheerAction action) {
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone IN ('HAND','CHEER_DECK')
            """,
            action.cheerCardInstanceId(),
            action.matchId(),
            action.actorUserId()
        );
        if (updated != 1) {
            throw new IllegalStateException("附加 Cheer 失敗，請重新整理對戰狀態");
        }
    }

    private Long insertAttachment(Long targetHolomemId, Long cheerCardInstanceId, String cheerCardId) {
        return jdbcTemplate.query(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
            VALUES (?, ?, ?, FALSE)
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            targetHolomemId,
            cheerCardInstanceId,
            cheerCardId
        );
    }
}
