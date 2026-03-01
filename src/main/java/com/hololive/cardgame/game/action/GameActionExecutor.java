package com.hololive.cardgame.game.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GameActionExecutor {

    private final JdbcTemplate jdbcTemplate;

    public GameActionExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ActionResult> execute(EffectContext context, List<AtomicAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<ActionResult> results = new ArrayList<>();
        for (AtomicAction action : actions) {
            results.add(executeOne(context, action));
        }
        return results;
    }

    private ActionResult executeOne(EffectContext context, AtomicAction action) {
        if (action instanceof MoveZoneAction moveZoneAction) {
            return executeMoveZone(context, moveZoneAction);
        }
        if (action instanceof DrawAction drawAction) {
            return executeDraw(context, drawAction);
        }
        if (action instanceof DamageAction damageAction) {
            return executeDamage(context, damageAction);
        }
        if (action instanceof ReduceLifeAction reduceLifeAction) {
            return executeReduceLife(context, reduceLifeAction);
        }
        if (action instanceof SendCheerAction sendCheerAction) {
            return executeSendCheer(context, sendCheerAction);
        }
        return ActionResult.failure("UNKNOWN", "UNSUPPORTED_ACTION");
    }

    private ActionResult executeDraw(EffectContext context, DrawAction action) {
        if (context == null || context.matchId() == null || action == null || action.ownerUserId() == null) {
            return ActionResult.failure("DRAW", "INVALID_ARGUMENTS");
        }
        int drawCount = Math.max(action.drawCount(), 0);
        if (drawCount <= 0) {
            return ActionResult.success("DRAW", Map.of("movedCount", 0, "cardInstanceIds", List.of()));
        }
        String fromZone = normalizeZone(action.fromZone());
        String toZone = normalizeZone(action.toZone());
        List<Long> targetCardInstanceIds = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            ORDER BY order_index NULLS LAST, id
            LIMIT ?
            """,
            (rs, rowNum) -> rs.getLong("id"),
            context.matchId(),
            action.ownerUserId(),
            fromZone,
            drawCount
        );
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            context.matchId(),
            action.ownerUserId(),
            toZone
        );
        int order = nextOrder == null ? 0 : nextOrder;
        List<Long> moved = new ArrayList<>();
        for (Long cardInstanceId : targetCardInstanceIds) {
            order += 1;
            int updated = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = ?,
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = ?
                """,
                toZone,
                order,
                cardInstanceId,
                context.matchId(),
                action.ownerUserId(),
                fromZone
            );
            if (updated == 1) {
                moved.add(cardInstanceId);
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fromZone", fromZone);
        details.put("toZone", toZone);
        details.put("requestedCount", drawCount);
        details.put("movedCount", moved.size());
        details.put("cardInstanceIds", moved);
        return ActionResult.success("DRAW", details);
    }

    private ActionResult executeMoveZone(EffectContext context, MoveZoneAction action) {
        if (
            context == null ||
            context.matchId() == null ||
            action == null ||
            action.cardInstanceId() == null ||
            action.ownerUserId() == null
        ) {
            return ActionResult.failure("MOVE_ZONE", "INVALID_ARGUMENTS");
        }
        String fromZone = normalizeZone(action.fromZone());
        String toZone = normalizeZone(action.toZone());
        Integer targetOrder = action.targetOrderIndex();
        if (targetOrder == null) {
            targetOrder = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(order_index), 0) + 1
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = ?
                """,
                Integer.class,
                context.matchId(),
                action.ownerUserId(),
                toZone
            );
        }
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = ?,
                order_index = ?,
                is_face_down = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            toZone,
            targetOrder == null ? 1 : targetOrder,
            Boolean.TRUE.equals(action.faceDown()),
            action.cardInstanceId(),
            context.matchId(),
            action.ownerUserId(),
            fromZone
        );
        if (updated != 1) {
            return ActionResult.failure("MOVE_ZONE", "CARD_NOT_MOVED");
        }
        return ActionResult.success(
            "MOVE_ZONE",
            Map.of(
                "cardInstanceId", action.cardInstanceId(),
                "fromZone", fromZone,
                "toZone", toZone,
                "orderIndex", targetOrder == null ? 1 : targetOrder
            )
        );
    }

    private ActionResult executeDamage(EffectContext context, DamageAction action) {
        if (context == null || context.matchId() == null || action == null || action.targetHolomemId() == null) {
            return ActionResult.failure("DAMAGE", "INVALID_ARGUMENTS");
        }
        int amount = Math.max(action.amount(), 0);
        if (amount <= 0) {
            return ActionResult.success("DAMAGE", Map.of("targetHolomemId", action.targetHolomemId(), "damageApplied", 0));
        }
        int updated = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = COALESCE(damage_taken, 0) + ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
            """,
            amount,
            action.targetHolomemId(),
            context.matchId()
        );
        if (updated != 1) {
            return ActionResult.failure("DAMAGE", "TARGET_NOT_FOUND");
        }
        return ActionResult.success(
            "DAMAGE",
            Map.of(
                "targetHolomemId", action.targetHolomemId(),
                "damageApplied", amount,
                "source", action.source() == null ? "" : action.source()
            )
        );
    }

    private ActionResult executeReduceLife(EffectContext context, ReduceLifeAction action) {
        if (context == null || context.matchId() == null || action == null || action.targetUserId() == null) {
            return ActionResult.failure("REDUCE_LIFE", "INVALID_ARGUMENTS");
        }
        int amount = Math.max(action.amount(), 0);
        if (amount <= 0) {
            return ActionResult.success("REDUCE_LIFE", Map.of("reduced", 0, "lifeCardInstanceIds", List.of()));
        }
        List<Long> movedLifeCards = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            Long lifeCardInstanceId = jdbcTemplate.query(
                """
                SELECT id
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'LIFE'
                ORDER BY order_index NULLS LAST, id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                context.matchId(),
                action.targetUserId()
            );
            if (lifeCardInstanceId == null) {
                break;
            }
            Integer archiveOrder = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(order_index), 0) + 1
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'ARCHIVE'
                """,
                Integer.class,
                context.matchId(),
                action.targetUserId()
            );
            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'ARCHIVE',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'LIFE'
                """,
                archiveOrder == null ? 1 : archiveOrder,
                lifeCardInstanceId,
                context.matchId(),
                action.targetUserId()
            );
            if (moved != 1) {
                break;
            }
            movedLifeCards.add(lifeCardInstanceId);
            jdbcTemplate.update(
                """
                UPDATE match_players
                SET current_life = GREATEST(COALESCE(current_life, 0) - 1, 0),
                    updated_at = CURRENT_TIMESTAMP
                WHERE match_id = ?
                  AND user_id = ?
                """,
                context.matchId(),
                action.targetUserId()
            );
        }
        if (movedLifeCards.isEmpty()) {
            return ActionResult.failure("REDUCE_LIFE", "NO_LIFE_CARD");
        }
        return ActionResult.success(
            "REDUCE_LIFE",
            Map.of(
                "targetUserId", action.targetUserId(),
                "requested", amount,
                "reduced", movedLifeCards.size(),
                "lifeCardInstanceIds", movedLifeCards,
                "reason", action.reason() == null ? "" : action.reason()
            )
        );
    }

    private ActionResult executeSendCheer(EffectContext context, SendCheerAction action) {
        if (
            context == null ||
            context.matchId() == null ||
            context.actorUserId() == null ||
            action == null ||
            action.cheerCardInstanceId() == null ||
            action.targetHolomemId() == null
        ) {
            return ActionResult.failure("SEND_CHEER", "INVALID_ARGUMENTS");
        }
        Long ownerUserId = context.actorUserId();
        Long targetHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            action.targetHolomemId(),
            context.matchId(),
            ownerUserId
        );
        if (targetHolomemId == null) {
            return ActionResult.failure("SEND_CHEER", "TARGET_NOT_FOUND");
        }
        Map<String, Object> sourceCard = jdbcTemplate.query(
            """
            SELECT id, card_id, zone
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
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
            action.cheerCardInstanceId(),
            context.matchId(),
            ownerUserId
        );
        if (sourceCard == null) {
            return ActionResult.failure("SEND_CHEER", "SOURCE_NOT_FOUND");
        }
        String sourceZone = normalizeZone((String) sourceCard.get("zone"));
        if (!List.of("CHEER_DECK", "ARCHIVE", "HAND").contains(sourceZone)) {
            return ActionResult.failure("SEND_CHEER", "INVALID_SOURCE_ZONE");
        }
        String cheerCardId = (String) sourceCard.get("card_id");
        Integer cheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cheer_cards WHERE card_id = ?",
            Integer.class,
            cheerCardId
        );
        if (cheerCount == null || cheerCount <= 0) {
            return ActionResult.failure("SEND_CHEER", "SOURCE_NOT_CHEER");
        }
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
              AND zone IN ('CHEER_DECK','ARCHIVE','HAND')
            """,
            action.cheerCardInstanceId(),
            context.matchId(),
            ownerUserId
        );
        if (moved != 1) {
            return ActionResult.failure("SEND_CHEER", "SOURCE_MOVE_FAILED");
        }
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
            VALUES (?, ?, FALSE)
            """,
            targetHolomemId,
            cheerCardId
        );
        return ActionResult.success(
            "SEND_CHEER",
            Map.of(
                "cheerCardInstanceId", action.cheerCardInstanceId(),
                "cheerCardId", cheerCardId,
                "sourceZone", sourceZone,
                "targetHolomemId", targetHolomemId,
                "source", action.source() == null ? "" : action.source()
            )
        );
    }

    private String normalizeZone(String zone) {
        if (zone == null) {
            return "";
        }
        return zone.trim().toUpperCase(Locale.ROOT);
    }
}
