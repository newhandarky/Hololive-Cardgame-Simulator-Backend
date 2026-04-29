package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import org.springframework.jdbc.core.JdbcTemplate;

class PassiveGiftTriggerActionWriter {

    static final String TRIGGER_TYPE_PASSIVE_INCOMING_DAMAGE_REDUCTION = "PASSIVE_INCOMING_DAMAGE_REDUCTION";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;

    PassiveGiftTriggerActionWriter(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        EffectTextParser effectTextParser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.effectTextParser = effectTextParser;
    }

    void appendIncomingDamageReductionTrigger(
        Long matchId,
        Long userId,
        int turnNumber,
        Long holderHolomemId,
        String giftText,
        int diceRoll
    ) {
        if (matchId == null || userId == null || turnNumber <= 0 || holderHolomemId == null) {
            return;
        }
        int nextActionOrder = resolveNextActionOrder(matchId, turnNumber);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("triggerType", TRIGGER_TYPE_PASSIVE_INCOMING_DAMAGE_REDUCTION);
        payload.put("giftHolderHolomemId", holderHolomemId);
        payload.put("giftText", nullToEmpty(giftText));
        payload.put("diceRoll", diceRoll);
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (
                match_id,
                user_id,
                turn_number,
                action_order,
                action_type,
                payload,
                executed_at
            ) VALUES (?, ?, ?, ?, 'GIFT_TRIGGER', CAST(? AS jsonb), CURRENT_TIMESTAMP)
            """,
            matchId,
            userId,
            turnNumber,
            nextActionOrder,
            effectTextParser.toJsonString(payload)
        );
    }

    private int resolveNextActionOrder(Long matchId, int turnNumber) {
        Integer maxOrder = jdbcTemplate.query(
            """
            SELECT COALESCE(MAX(action_order), 0)
            FROM match_actions
            WHERE match_id = ?
              AND turn_number = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            turnNumber
        );
        return (maxOrder == null ? 0 : maxOrder) + 1;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
