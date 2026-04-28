package com.hololive.cardgame.service;

import static com.hololive.cardgame.service.MatchEffectValueHelper.asLong;
import static com.hololive.cardgame.service.MatchEffectValueHelper.asText;
import static com.hololive.cardgame.service.MatchEffectValueHelper.toLongList;
import static com.hololive.cardgame.service.MatchEffectValueHelper.toTextList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchGiftTriggerContextService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;

    MatchGiftTriggerContextService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        EffectTextParser effectTextParser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.effectTextParser = effectTextParser;
    }

    void recordPerformancePhaseSnapshot(
        Long matchId,
        Long sourceUserId,
        Long affectedUserId,
        int turnNumber
    ) {
        if (matchId == null || affectedUserId == null || turnNumber <= 0) {
            return;
        }
        Integer currentLife = jdbcTemplate.query(
            """
            SELECT current_life
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt("current_life") : null,
            matchId,
            affectedUserId
        );
        Map<String, Integer> holomemDamage = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
            SELECT id, COALESCE(damage_taken, 0) AS damage_taken
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            ORDER BY id
            """,
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                holomemDamage.put(Long.toString(rs.getLong("id")), rs.getInt("damage_taken")),
            matchId,
            affectedUserId
        );
        jdbcTemplate.update(
            """
            DELETE FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'PERFORMANCE_SNAPSHOT'
              AND expires_turn = ?
            """,
            matchId,
            affectedUserId,
            turnNumber
        );
        jdbcTemplate.update(
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
            ) VALUES (?, ?, ?, ?, 'PERFORMANCE_SNAPSHOT', 0, ?, CAST(? AS jsonb))
            """,
            matchId,
            sourceUserId,
            affectedUserId,
            "SYSTEM",
            turnNumber,
            effectTextParser.toJsonString(
                Map.of(
                    "turnNumber", turnNumber,
                    "currentLife", currentLife == null ? 0 : currentLife,
                    "holomemDamage", holomemDamage
                )
            )
        );
    }

    Map<String, Object> loadGiftHolderSnapshot(Long matchId, Long userId, Long giftHolderHolomemId) {
        if (matchId == null || userId == null || giftHolderHolomemId == null || giftHolderHolomemId <= 0) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   h.zone,
                   h.current_level,
                   m.passive_effect_json::text AS passive_text
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Long holomemId = rs.getLong("holomem_id");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("holomem_id", holomemId);
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                row.put("current_level", rs.getString("current_level"));
                row.put("passive_text", rs.getString("passive_text"));
                List<Map<String, Object>> attachedCheers = jdbcTemplate.queryForList(
                    """
                    SELECT match_card_id,
                           cheer_card_id
                    FROM match_holomem_cheers
                    WHERE match_holomem_id = ?
                    ORDER BY id
                    """,
                    holomemId
                );
                List<Long> attachedCheerCardInstanceIds = new ArrayList<>();
                List<String> attachedCheerCardIds = new ArrayList<>();
                for (Map<String, Object> attachedCheer : attachedCheers) {
                    Long attachedCheerCardInstanceId = asLong(attachedCheer.get("match_card_id"));
                    String attachedCheerCardId = asText(attachedCheer.get("cheer_card_id"));
                    if (attachedCheerCardInstanceId != null && attachedCheerCardInstanceId > 0) {
                        attachedCheerCardInstanceIds.add(attachedCheerCardInstanceId);
                    }
                    if (StringUtils.hasText(attachedCheerCardId)) {
                        attachedCheerCardIds.add(attachedCheerCardId);
                    }
                }
                row.put("attached_cheer_card_instance_ids", attachedCheerCardInstanceIds);
                row.put("attached_cheer_card_ids", attachedCheerCardIds);
                List<Map<String, Object>> stackCards = jdbcTemplate.queryForList(
                    """
                    SELECT s.match_card_id,
                           mc.card_id
                    FROM match_holomem_stack_cards s
                    JOIN match_cards mc ON mc.id = s.match_card_id
                    WHERE s.match_holomem_id = ?
                    ORDER BY s.stack_order DESC, s.id DESC
                    """,
                    holomemId
                );
                List<Long> stackCardInstanceIds = new ArrayList<>();
                List<String> stackCardIds = new ArrayList<>();
                for (Map<String, Object> stackCard : stackCards) {
                    Long stackCardInstanceId = asLong(stackCard.get("match_card_id"));
                    String stackCardId = asText(stackCard.get("card_id"));
                    if (stackCardInstanceId != null && stackCardInstanceId > 0) {
                        stackCardInstanceIds.add(stackCardInstanceId);
                    }
                    if (StringUtils.hasText(stackCardId)) {
                        stackCardIds.add(stackCardId);
                    }
                }
                row.put("stack_card_instance_ids", stackCardInstanceIds);
                row.put("stack_card_ids", stackCardIds);
                return row;
            },
            matchId,
            userId,
            giftHolderHolomemId
        );
    }

    List<Map<String, Object>> loadSelfDownedFanSupportSnapshots(
        Long matchId,
        Long ownerUserId,
        Long holderHolomemId
    ) {
        if (matchId == null || ownerUserId == null || holderHolomemId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
            SELECT hs.match_card_id AS support_card_instance_id,
                   hs.support_card_id,
                   COALESCE(sc.effect_json ->> 'rawText', '') AS raw_text
            FROM match_holomem_supports hs
            JOIN support_cards sc ON sc.card_id = hs.support_card_id
            JOIN match_cards mc ON mc.id = hs.match_card_id
            WHERE hs.match_holomem_id = ?
              AND hs.support_type = 'FAN'
              AND hs.support_card_id = 'HBP01-124'
              AND mc.match_id = ?
              AND mc.owner_user_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("supportCardInstanceId", rs.getLong("support_card_instance_id"));
                row.put("supportCardId", rs.getString("support_card_id"));
                row.put("rawText", rs.getString("raw_text"));
                return row;
            },
            holderHolomemId,
            matchId,
            ownerUserId
        );
    }

    void appendStoredGiftExecutionContext(ObjectNode giftNode, Map<String, Object> storedTriggerContext) {
        if (giftNode == null || storedTriggerContext == null || storedTriggerContext.isEmpty()) {
            return;
        }
        Long giftHolderHolomemId = asLong(storedTriggerContext.get("giftHolderHolomemId"));
        if (giftHolderHolomemId != null && giftHolderHolomemId > 0) {
            giftNode.put("giftHolderHolomemId", giftHolderHolomemId);
        }
        List<Long> attachedCheerCardInstanceIds = toLongList(
            storedTriggerContext.get("giftHolderAttachedCheerCardInstanceIds")
        );
        if (attachedCheerCardInstanceIds.isEmpty()) {
            attachedCheerCardInstanceIds = toLongList(storedTriggerContext.get("attached_cheer_card_instance_ids"));
        }
        if (!attachedCheerCardInstanceIds.isEmpty()) {
            giftNode.set(
                "giftHolderAttachedCheerCardInstanceIds",
                objectMapper.valueToTree(attachedCheerCardInstanceIds)
            );
        }
        List<String> attachedCheerCardIds = toTextList(storedTriggerContext.get("giftHolderAttachedCheerCardIds"));
        if (attachedCheerCardIds.isEmpty()) {
            attachedCheerCardIds = toTextList(storedTriggerContext.get("attached_cheer_card_ids"));
        }
        if (!attachedCheerCardIds.isEmpty()) {
            giftNode.set(
                "giftHolderAttachedCheerCardIds",
                objectMapper.valueToTree(attachedCheerCardIds)
            );
        }
        List<Long> stackCardInstanceIds = toLongList(storedTriggerContext.get("giftHolderStackCardInstanceIds"));
        if (stackCardInstanceIds.isEmpty()) {
            stackCardInstanceIds = toLongList(storedTriggerContext.get("stack_card_instance_ids"));
        }
        if (!stackCardInstanceIds.isEmpty()) {
            giftNode.set(
                "giftHolderStackCardInstanceIds",
                objectMapper.valueToTree(stackCardInstanceIds)
            );
        }
        List<String> stackCardIds = toTextList(storedTriggerContext.get("giftHolderStackCardIds"));
        if (stackCardIds.isEmpty()) {
            stackCardIds = toTextList(storedTriggerContext.get("stack_card_ids"));
        }
        if (!stackCardIds.isEmpty()) {
            giftNode.set(
                "giftHolderStackCardIds",
                objectMapper.valueToTree(stackCardIds)
            );
        }
        List<Long> selectedCardInstanceIds = toLongList(storedTriggerContext.get("selectedCardInstanceIds"));
        if (!selectedCardInstanceIds.isEmpty()) {
            giftNode.set("selectedCardInstanceIds", objectMapper.valueToTree(selectedCardInstanceIds));
        }
    }
}
