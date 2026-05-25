package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchSpecialDamagePreventionResolverService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final MatchGiftTriggerConditionService giftTriggerConditionService;

    MatchSpecialDamagePreventionResolverService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        MatchGiftTriggerConditionService giftTriggerConditionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.giftTriggerConditionService = giftTriggerConditionService;
    }

    boolean isSpecialDamageImmunityActive(
        Long matchId,
        Long affectedUserId,
        int currentTurn,
        String targetZone
    ) {
        if (
            matchId == null
                || affectedUserId == null
                || currentTurn <= 0
                || !"BACK".equals(normalize(targetZone))
        ) {
            return false;
        }
        Integer count = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ACTION_LOCK'
              AND expires_turn >= ?
              AND payload::text LIKE '%"SPECIAL_DAMAGE_IMMUNITY"%'
              AND payload::text LIKE '%"BACK"%'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            affectedUserId,
            currentTurn
        );
        return count != null && count > 0;
    }

    Map<String, Object> tryActivateHsd13012SpecialDamageImmunity(
        Long matchId,
        Long sourceUserId,
        Long defendingUserId,
        Long targetHolomemId,
        String targetZone,
        int currentTurn
    ) {
        if (
            matchId == null
                || sourceUserId == null
                || defendingUserId == null
                || targetHolomemId == null
                || currentTurn <= 0
        ) {
            return null;
        }
        if (Objects.equals(sourceUserId, defendingUserId)) {
            return null;
        }
        if (!"BACK".equals(normalize(targetZone))) {
            return null;
        }
        if (!isOpponentTurnForUser(matchId, defendingUserId)) {
            return null;
        }
        List<Map<String, Object>> holders = jdbcTemplate.queryForList(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   m.passive_effect_json::text AS passive_text
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.card_id = 'HSD13-012'
            ORDER BY h.id
            """,
            matchId,
            defendingUserId
        );
        if (holders.isEmpty()) {
            return null;
        }
        for (Map<String, Object> holder : holders) {
            Long holderHolomemId = asLong(holder.get("holomem_id"));
            Long holderCardInstanceId = asLong(holder.get("match_card_id"));
            String giftText = loadGiftEffectText(asText(holder.get("passive_text")));
            if (!StringUtils.hasText(giftText)) {
                continue;
            }
            if (!giftText.contains("自分のバックホロメンが相手から特殊ダメージを受ける時")) {
                continue;
            }
            if (!giftText.contains("このターンの間、自分のバックホロメン全員は特殊ダメージを受けない")) {
                continue;
            }
            if (!giftTriggerConditionService.matchesTurnOwnershipCondition(matchId, defendingUserId, giftText)) {
                continue;
            }
            Long archivedStackCardInstanceId = archiveOneStackCardFromHolder(
                matchId,
                defendingUserId,
                holderHolomemId,
                holderCardInstanceId
            );
            if (archivedStackCardInstanceId == null || archivedStackCardInstanceId <= 0) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actions", List.of("SPECIAL_DAMAGE_IMMUNITY"));
            payload.put("zones", List.of("BACK"));
            payload.put("sourceCardId", asText(holder.get("card_id")));
            payload.put("holderHolomemId", holderHolomemId);
            payload.put("holderCardInstanceId", holderCardInstanceId);
            payload.put("rawText", giftText);
            int inserted = jdbcTemplate.update(
                """
                INSERT INTO match_turn_effects (
                    match_id,
                    source_user_id,
                    affected_user_id,
                    effect_type,
                    stat_type,
                    modifier_value,
                    expires_turn,
                    payload
                ) VALUES (?, ?, ?, ?, 'ACTION_LOCK', 1, ?, CAST(? AS jsonb))
                """,
                matchId,
                defendingUserId,
                defendingUserId,
                "BUFF",
                currentTurn,
                effectTextParser.toJsonString(payload)
            );
            if (inserted != 1) {
                return null;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("triggerType", "SPECIAL_DAMAGE_RECEIVED");
            summary.put("preventedDamage", true);
            summary.put("holderHolomemId", holderHolomemId);
            summary.put("holderCardInstanceId", holderCardInstanceId);
            summary.put("holderCardId", asText(holder.get("card_id")));
            summary.put("archivedStackCardInstanceId", archivedStackCardInstanceId);
            summary.put("expiresTurn", currentTurn);
            summary.put("targetHolomemId", targetHolomemId);
            return summary;
        }
        return null;
    }

    private Long archiveOneStackCardFromHolder(
        Long matchId,
        Long userId,
        Long holderHolomemId,
        Long holderCardInstanceId
    ) {
        if (matchId == null || userId == null || holderHolomemId == null || holderCardInstanceId == null) {
            return null;
        }
        Long stackCardInstanceId = jdbcTemplate.query(
            """
            SELECT s.match_card_id
            FROM match_holomem_stack_cards s
            WHERE s.match_holomem_id = ?
              AND s.match_card_id <> ?
            ORDER BY s.stack_order DESC, s.id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            holderHolomemId,
            holderCardInstanceId
        );
        if (stackCardInstanceId == null || stackCardInstanceId <= 0) {
            return null;
        }
        int archiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
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
            """,
            archiveOrder,
            stackCardInstanceId,
            matchId,
            userId
        );
        if (moved != 1) {
            return null;
        }
        jdbcTemplate.update(
            """
            DELETE FROM match_holomem_stack_cards
            WHERE match_holomem_id = ?
              AND match_card_id = ?
            """,
            holderHolomemId,
            stackCardInstanceId
        );
        return stackCardInstanceId;
    }

    private boolean isOpponentTurnForUser(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return false;
        }
        Long currentTurnPlayerId = jdbcTemplate.query(
            """
            SELECT current_turn_player_id
            FROM matches
            WHERE id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? asLong(rs.getObject("current_turn_player_id")) : null,
            matchId
        );
        return currentTurnPlayerId != null && !Objects.equals(currentTurnPlayerId, userId);
    }

    private int nextZoneOrder(Long matchId, Long userId, String zone) {
        Integer next = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        return next == null ? 1 : next;
    }

    private String loadGiftEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("ギフト")) {
            return null;
        }
        return normalizeGiftText(passiveText);
    }

    private String normalizeGiftText(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return "";
        }
        String normalized = passiveText
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("{", " ")
            .replace("}", " ")
            .replace("\"", " ")
            .replace(":", " ");
        int idx = normalized.indexOf("ギフト");
        if (idx < 0) {
            return normalized.trim();
        }
        String trimmed = normalized.substring(idx).trim();
        String[] stopTokens = { "ブルームエフェクト", "コラボエフェクト", "エクストラ" };
        int end = trimmed.length();
        for (String token : stopTokens) {
            int tokenIdx = trimmed.indexOf(token, "ギフト".length());
            if (tokenIdx > 0 && tokenIdx < end) {
                end = tokenIdx;
            }
        }
        return trimmed.substring(0, end).trim();
    }

    private String normalize(Object value) {
        return MatchEffectValueHelper.normalize(value);
    }

    private Long asLong(Object value) {
        return MatchEffectValueHelper.asLong(value);
    }

    private String asText(Object value) {
        return MatchEffectValueHelper.asText(value);
    }
}
