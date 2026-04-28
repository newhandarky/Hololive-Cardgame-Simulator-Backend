package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AttackOfficialOshiSelfDownedEffectService
    implements AttackDefenderGiftFollowupService.OfficialOshiSelfDownedEffectResolver {

    private final JdbcTemplate jdbcTemplate;

    public AttackOfficialOshiSelfDownedEffectService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> resolveOfficialOshiSelfDownedEffects(AttackDefenderGiftFollowupContext context) {
        if (context == null) {
            return Map.of();
        }
        String oshiCardId = loadPlayerOshiCardId(context.matchId(), context.defenderUserId());
        if (
            !StringUtils.hasText(oshiCardId)
                || context.downedTarget() == null
                || context.holderSnapshot() == null
                || context.holderSnapshot().isEmpty()
        ) {
            return Map.of();
        }

        List<Map<String, Object>> executed = new ArrayList<>();
        if ("HBP01-004".equals(oshiCardId)) {
            Map<String, Object> hbp01004 = applyHbp01004OshiSelfDownedReattach(context);
            if (!hbp01004.isEmpty()) {
                executed.add(hbp01004);
            }
        }
        if ("HBP01-006".equals(oshiCardId)) {
            Map<String, Object> hbp01006 = applyHbp01006OshiSelfDownedReturnStack(context);
            if (!hbp01006.isEmpty()) {
                executed.add(hbp01006);
            }
        }
        if (executed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_REACTIVE_SELF_DOWNED_EFFECTS");
        summary.put("oshiCardId", oshiCardId);
        summary.put("executedEffects", executed);
        summary.put("applied", true);
        return summary;
    }

    private Map<String, Object> applyHbp01004OshiSelfDownedReattach(AttackDefenderGiftFollowupContext context) {
        if (
            Objects.equals(context.defenderUserId(), context.attackerUserId())
                || !canUseOshiSkill(context.matchId(), context.defenderUserId(), "NORMAL", 2)
        ) {
            return Map.of();
        }
        Long downedHolomemId = asLong(context.holderSnapshot().get("holomem_id"));
        Long targetHolomemId = loadFirstOwnOtherHolomemId(context.matchId(), context.defenderUserId(), downedHolomemId);
        if (targetHolomemId == null) {
            return Map.of();
        }
        List<Long> movableGreenCheerIds = filterCheerCardInstanceIdsByColor(
            context.matchId(),
            context.defenderUserId(),
            toLongList(context.holderSnapshot().get("attached_cheer_card_instance_ids")),
            "GREEN"
        );
        if (movableGreenCheerIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> holopowerPayment = consumeHolopowerCostToArchive(context.matchId(), context.defenderUserId(), 2);
        markOshiSkillUsed(context.matchId(), context.defenderUserId(), "NORMAL");
        List<String> movedCheerCardIds = new ArrayList<>();
        List<Long> movedCheerRowIds = new ArrayList<>();
        for (Long cheerCardInstanceId : movableGreenCheerIds) {
            String cheerCardId = moveCheerCardInstanceToHolomem(
                context.matchId(),
                context.defenderUserId(),
                cheerCardInstanceId,
                targetHolomemId
            );
            if (StringUtils.hasText(cheerCardId)) {
                movedCheerCardIds.add(cheerCardId);
                Long rowId = loadAttachedCheerRowId(targetHolomemId, cheerCardInstanceId);
                if (rowId != null) {
                    movedCheerRowIds.add(rowId);
                }
            }
        }
        if (movedCheerCardIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> reattach = new LinkedHashMap<>();
        reattach.put("effectType", "REATTACH");
        reattach.put("moveRequested", movableGreenCheerIds.size());
        reattach.put("moveApplied", movedCheerCardIds.size());
        reattach.put("targetHolomemId", targetHolomemId);
        reattach.put("movedCheerCardIds", movedCheerCardIds);
        reattach.put("movedCheerRowIds", movedCheerRowIds);
        reattach.put("sourceMode", "DOWNED_GREEN_CHEER");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_SKILL_TRIGGER");
        summary.put("oshiCardId", "HBP01-004");
        summary.put("skillType", "NORMAL");
        summary.put("skillName", "野兎たち～");
        summary.put("triggerType", "SELF_DOWNED");
        summary.put("holopowerCost", 2);
        summary.put("holopowerPayment", holopowerPayment);
        summary.put("executedEffects", List.of(reattach));
        summary.put("applied", true);
        return summary;
    }

    private Map<String, Object> applyHbp01006OshiSelfDownedReturnStack(AttackDefenderGiftFollowupContext context) {
        if (
            Objects.equals(context.defenderUserId(), context.attackerUserId())
                || context.downedTarget() == null
                || !"RED".equals(normalizeZone(context.downedTarget().mainColor()))
                || !canUseOshiSkill(context.matchId(), context.defenderUserId(), "SP", 2)
        ) {
            return Map.of();
        }
        List<Long> stackCardInstanceIds = toLongList(context.holderSnapshot().get("stack_card_instance_ids"));
        if (stackCardInstanceIds.isEmpty()) {
            Long matchCardInstanceId = asLong(context.holderSnapshot().get("match_card_id"));
            if (matchCardInstanceId != null && matchCardInstanceId > 0) {
                stackCardInstanceIds = List.of(matchCardInstanceId);
            }
        }
        if (stackCardInstanceIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> holopowerPayment = consumeHolopowerCostToArchive(context.matchId(), context.defenderUserId(), 2);
        markOshiSkillUsed(context.matchId(), context.defenderUserId(), "SP");
        Long restoredLifeCardInstanceId = restoreLostLifeFromArtSummary(
            context.matchId(),
            context.defenderUserId(),
            context.artSummary()
        );
        List<Long> returnedStackCardInstanceIds = moveArchivedCardsToHand(
            context.matchId(),
            context.defenderUserId(),
            stackCardInstanceIds
        );

        Map<String, Object> returnToHand = new LinkedHashMap<>();
        returnToHand.put("effectType", "RETURN_TO_HAND");
        returnToHand.put("moveRequested", stackCardInstanceIds.size());
        returnToHand.put("moveApplied", returnedStackCardInstanceIds.size());
        returnToHand.put("movedCardInstanceIds", returnedStackCardInstanceIds);
        returnToHand.put("sourceMode", "DOWNED_HOLOMEM_STACK");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_SKILL_TRIGGER");
        summary.put("oshiCardId", "HBP01-006");
        summary.put("skillType", "SP");
        summary.put("skillName", "Rise from the ashes");
        summary.put("triggerType", "SELF_DOWNED");
        summary.put("holopowerCost", 2);
        summary.put("holopowerPayment", holopowerPayment);
        summary.put("restoredLifeCardInstanceId", restoredLifeCardInstanceId);
        summary.put("lifeLossModifier", restoredLifeCardInstanceId == null ? 0 : -1);
        summary.put("executedEffects", List.of(returnToHand));
        summary.put("applied", true);
        return summary;
    }

    private String loadPlayerOshiCardId(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT oshi_card_id
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("oshi_card_id") : null,
            matchId,
            userId
        );
    }

    private boolean canUseOshiSkill(Long matchId, Long userId, String skillType, int holopowerCost) {
        if (matchId == null || userId == null || !StringUtils.hasText(skillType)) {
            return false;
        }
        Map<String, Object> row = jdbcTemplate.query(
            """
            SELECT skill_used_this_turn,
                   sp_skill_used
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("skill_used_this_turn", rs.getBoolean("skill_used_this_turn"));
                result.put("sp_skill_used", rs.getBoolean("sp_skill_used"));
                return result;
            },
            matchId,
            userId
        );
        if (row == null) {
            return false;
        }
        if (toBoolean(row.get("skill_used_this_turn"))) {
            return false;
        }
        if ("SP".equals(normalizeZone(skillType)) && toBoolean(row.get("sp_skill_used"))) {
            return false;
        }
        Integer holopowerCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HOLOPOWER'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId
        );
        return holopowerCount != null && holopowerCount >= Math.max(holopowerCost, 0);
    }

    private void markOshiSkillUsed(Long matchId, Long userId, String skillType) {
        if (matchId == null || userId == null) {
            return;
        }
        boolean sp = "SP".equals(normalizeZone(skillType));
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET skill_used_this_turn = TRUE,
                sp_skill_used = CASE WHEN ? THEN TRUE ELSE sp_skill_used END,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            sp,
            matchId,
            userId
        );
    }

    private Long loadFirstOwnOtherHolomemId(Long matchId, Long ownerUserId, Long excludedHolomemId) {
        if (matchId == null || ownerUserId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
              AND (? IS NULL OR id <> ?)
            ORDER BY CASE zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            excludedHolomemId,
            excludedHolomemId
        );
    }

    private List<Long> filterCheerCardInstanceIdsByColor(
        Long matchId,
        Long ownerUserId,
        List<Long> cheerCardInstanceIds,
        String color
    ) {
        if (matchId == null || ownerUserId == null || cheerCardInstanceIds == null || cheerCardInstanceIds.isEmpty()) {
            return List.of();
        }
        String normalizedColor = normalizeZone(color);
        List<Long> matched = new ArrayList<>();
        for (Long cheerCardInstanceId : cheerCardInstanceIds) {
            if (cheerCardInstanceId == null || cheerCardInstanceId <= 0) {
                continue;
            }
            String cardColor = jdbcTemplate.query(
                """
                SELECT cc.color
                FROM match_cards mc
                JOIN cheer_cards cc ON cc.card_id = mc.card_id
                WHERE mc.id = ?
                  AND mc.match_id = ?
                  AND mc.owner_user_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("color") : null,
                cheerCardInstanceId,
                matchId,
                ownerUserId
            );
            if (normalizedColor.equals(normalizeZone(cardColor))) {
                matched.add(cheerCardInstanceId);
            }
        }
        return matched;
    }

    private String moveCheerCardInstanceToHolomem(
        Long matchId,
        Long ownerUserId,
        Long cheerCardInstanceId,
        Long targetHolomemId
    ) {
        if (matchId == null || ownerUserId == null || cheerCardInstanceId == null || targetHolomemId == null) {
            return null;
        }
        String cheerCardId = jdbcTemplate.query(
            """
            SELECT card_id
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone IN ('ARCHIVE', 'STAGE')
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("card_id") : null,
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
        if (!StringUtils.hasText(cheerCardId)) {
            return null;
        }
        jdbcTemplate.update(
            """
            DELETE FROM match_holomem_cheers c
            USING match_holomems h
            WHERE c.match_holomem_id = h.id
              AND c.match_card_id = ?
              AND h.match_id = ?
              AND h.owner_user_id = ?
            """,
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
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
              AND zone IN ('ARCHIVE', 'STAGE')
            """,
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
        if (moved != 1) {
            return null;
        }
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
            VALUES (?, ?, ?, FALSE)
            """,
            targetHolomemId,
            cheerCardInstanceId,
            cheerCardId
        );
        return cheerCardId;
    }

    private Long loadAttachedCheerRowId(Long targetHolomemId, Long cheerCardInstanceId) {
        if (targetHolomemId == null || cheerCardInstanceId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomem_cheers
            WHERE match_holomem_id = ?
              AND match_card_id = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            targetHolomemId,
            cheerCardInstanceId
        );
    }

    private Long restoreLostLifeFromArtSummary(Long matchId, Long ownerUserId, Map<String, Object> artSummary) {
        if (matchId == null || ownerUserId == null || artSummary == null || artSummary.isEmpty()) {
            return null;
        }
        Long lostLifeCardInstanceId = asLong(artSummary.get("lostLifeCardInstanceId"));
        if (lostLifeCardInstanceId == null || lostLifeCardInstanceId <= 0) {
            List<Long> ids = toLongList(artSummary.get("lostLifeCardInstanceIds"));
            if (!ids.isEmpty()) {
                lostLifeCardInstanceId = ids.get(0);
            }
        }
        if (lostLifeCardInstanceId == null || lostLifeCardInstanceId <= 0) {
            return null;
        }
        Integer nextLifeOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1) - 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'LIFE'
            """,
            Integer.class,
            matchId,
            ownerUserId
        );
        int restored = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'LIFE',
                order_index = ?,
                is_face_down = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            nextLifeOrder == null ? 0 : nextLifeOrder,
            lostLifeCardInstanceId,
            matchId,
            ownerUserId
        );
        if (restored != 1) {
            return null;
        }
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = COALESCE(current_life, 0) + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            ownerUserId
        );
        artSummary.put("lifeReduced", false);
        artSummary.put("lostLifeCardInstanceId", null);
        artSummary.put("lostLifeCardInstanceIds", List.of());
        return lostLifeCardInstanceId;
    }

    private List<Long> moveArchivedCardsToHand(Long matchId, Long ownerUserId, List<Long> cardInstanceIds) {
        if (matchId == null || ownerUserId == null || cardInstanceIds == null || cardInstanceIds.isEmpty()) {
            return List.of();
        }
        Integer nextHandOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            Integer.class,
            matchId,
            ownerUserId
        );
        int order = nextHandOrder == null ? 1 : nextHandOrder;
        List<Long> moved = new ArrayList<>();
        for (Long cardInstanceId : cardInstanceIds) {
            if (cardInstanceId == null || cardInstanceId <= 0) {
                continue;
            }
            int updated = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'HAND',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'ARCHIVE'
                """,
                order++,
                cardInstanceId,
                matchId,
                ownerUserId
            );
            if (updated == 1) {
                moved.add(cardInstanceId);
            }
        }
        return moved;
    }

    private Map<String, Object> consumeHolopowerCostToArchive(Long matchId, Long userId, int holopowerCost) {
        int required = Math.max(holopowerCost, 0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("required", required);
        if (required <= 0) {
            summary.put("paid", 0);
            summary.put("archivedCardInstanceIds", List.of());
            summary.put("archivedCardIds", List.of());
            return summary;
        }
        List<Map<String, Object>> holopowerCards = jdbcTemplate.queryForList(
            """
            SELECT id, card_id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HOLOPOWER'
            ORDER BY order_index NULLS LAST, id
            LIMIT ?
            """,
            matchId,
            userId,
            required
        );
        if (holopowerCards.size() < required) {
            throw new GameRuleException(
                GameErrorCode.OSHI_SKILL_HOLOPOWER_INSUFFICIENT,
                "Holopower 不足，無法發動 OSHI 技能",
                Map.of("required", required, "available", holopowerCards.size())
            );
        }
        Integer nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            userId
        );
        int archiveOrder = nextArchiveOrder == null ? 1 : nextArchiveOrder;
        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> archivedCardIds = new ArrayList<>();
        for (Map<String, Object> row : holopowerCards) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asString(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
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
                  AND zone = 'HOLOPOWER'
                """,
                archiveOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                throw new IllegalStateException("Holopower 支付失敗，請重新整理後重試");
            }
            archivedCardInstanceIds.add(cardInstanceId);
            archivedCardIds.add(cardId);
        }
        summary.put("paid", archivedCardInstanceIds.size());
        summary.put("archivedCardInstanceIds", archivedCardInstanceIds);
        summary.put("archivedCardIds", archivedCardIds);
        return summary;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeZone(Object value) {
        String text = asString(value).trim();
        return text.isEmpty() ? "" : text.toUpperCase();
    }

    private List<Long> toLongList(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : values) {
            Long id = asLong(item);
            if (id == null || id <= 0 || result.contains(id)) {
                continue;
            }
            result.add(id);
        }
        return result;
    }
}
