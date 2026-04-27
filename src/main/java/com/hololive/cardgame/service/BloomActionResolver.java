package com.hololive.cardgame.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BloomActionResolver {

    private final JdbcTemplate jdbcTemplate;

    public BloomActionResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BloomResolutionResult resolve(BloomAction action, BloomValidationContext context) {
        if (action == null || context == null || context.match() == null) {
            throw new IllegalArgumentException("BLOOM 結算缺少必要上下文");
        }
        BloomSourceCardSnapshot sourceCard = context.sourceCard();
        BloomTargetSnapshot target = context.target();
        if (sourceCard == null || target == null) {
            throw new IllegalArgumentException("BLOOM 結算缺少來源或目標");
        }

        moveSourceCardToStage(action);
        recordHolomemStackCard(target.holomemId(), action.sourceCardInstanceId());
        updateTargetHolomem(action, context);
        if (target.extraBloomAllowanceId() != null) {
            consumeExtraBloomAllowance(target.extraBloomAllowanceId(), action.matchId(), action.actorUserId());
        }

        int stackDepth = countHolomemStackDepth(target.holomemId());
        boolean bloomLevelOverrideApplied = !isBloomLevelNextStep(target.topLevelType(), sourceCard.levelType()) &&
            target.levelOverrideAllowed();

        return new BloomResolutionResult(
            context.match(),
            action.actorUserId(),
            context.currentTurnNumber(),
            action.sourceCardInstanceId(),
            sourceCard.cardId(),
            sourceCard.levelType(),
            target.holomemId(),
            action.targetHolomemCardInstanceId(),
            target.topCardId(),
            target.topLevelType(),
            target.zone(),
            target.damageTaken(),
            stackDepth,
            bloomLevelOverrideApplied,
            target.extraBloomAllowanceId(),
            Map.of(),
            Map.of(),
            Map.of(),
            null
        );
    }

    private void moveSourceCardToStage(BloomAction action) {
        int moved = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            action.sourceCardInstanceId(),
            action.matchId(),
            action.actorUserId()
        );
        if (moved != 1) {
            throw new IllegalStateException("BLOOM 失敗：卡片移動異常");
        }
    }

    private void recordHolomemStackCard(Long matchHolomemId, Long matchCardId) {
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(stack_order), 0) + 1
            FROM match_holomem_stack_cards
            WHERE match_holomem_id = ?
            """,
            Integer.class,
            matchHolomemId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, ?)
            ON CONFLICT (match_card_id) DO NOTHING
            """,
            matchHolomemId,
            matchCardId,
            nextOrder == null ? 1 : nextOrder
        );
    }

    private void updateTargetHolomem(BloomAction action, BloomValidationContext context) {
        int updated = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET match_card_id = ?,
                card_id = ?,
                current_level = ?,
                last_bloom_turn = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            action.sourceCardInstanceId(),
            context.sourceCard().cardId(),
            context.sourceCard().levelType(),
            context.currentTurnNumber(),
            context.target().holomemId(),
            action.matchId(),
            action.actorUserId()
        );
        if (updated != 1) {
            throw new IllegalStateException("BLOOM 失敗：目標 Holomem 更新異常");
        }
    }

    private void consumeExtraBloomAllowance(Long allowanceId, Long matchId, Long userId) {
        jdbcTemplate.update(
            """
            DELETE FROM match_turn_effects
            WHERE id = ?
              AND match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ALLOW_EXTRA_BLOOM'
            """,
            allowanceId,
            matchId,
            userId
        );
    }

    private int countHolomemStackDepth(Long matchHolomemId) {
        Integer depth = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_stack_cards WHERE match_holomem_id = ?",
            Integer.class,
            matchHolomemId
        );
        return depth == null || depth <= 0 ? 1 : depth;
    }

    private boolean isBloomLevelNextStep(String fromLevelType, String toLevelType) {
        int fromRank = resolveBloomLevelRank(fromLevelType);
        int toRank = resolveBloomLevelRank(toLevelType);
        return fromRank >= 0 && toRank == fromRank + 1;
    }

    private int resolveBloomLevelRank(String levelType) {
        String normalized = levelType == null ? "" : levelType.trim().toUpperCase();
        return switch (normalized) {
            case "DEBUT" -> 0;
            case "FIRST" -> 1;
            case "SECOND" -> 2;
            case "BUZZ" -> 3;
            default -> -1;
        };
    }
}
