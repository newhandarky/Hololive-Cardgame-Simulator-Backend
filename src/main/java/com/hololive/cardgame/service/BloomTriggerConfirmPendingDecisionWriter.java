package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class BloomTriggerConfirmPendingDecisionWriter {

    private static final String INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM = "TRIGGER_EFFECT_CONFIRM";
    private static final String PENDING_STATUS = "PENDING";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    BloomTriggerConfirmPendingDecisionWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    BloomFollowupDecision createTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        String message,
        List<Map<String, Object>> cards,
        int turnNumber,
        Map<String, Object> additionalContext
    ) {
        if (hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的互動，請先完成確認");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
        context.put("sourceActionType", "BLOOM");
        context.put("title", "確認 Bloom 效果");
        context.put("message", message);
        context.put("cards", cards == null ? List.of() : cards);
        context.put("turnNumber", turnNumber);
        if (additionalContext != null && !additionalContext.isEmpty()) {
            context.putAll(additionalContext);
        }

        Long decisionId = jdbcTemplate.query(
            """
            INSERT INTO match_pending_decisions (
                match_id,
                user_id,
                decision_type,
                source_action_type,
                source_card_instance_id,
                source_card_id,
                effect_type,
                min_select,
                max_select,
                status,
                context_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM,
            "BLOOM",
            sourceCardInstanceId,
            sourceCardId,
            "BLOOM_EFFECT",
            0,
            0,
            PENDING_STATUS,
            toJson(context)
        );
        if (decisionId == null) {
            return null;
        }
        return new BloomFollowupDecision(decisionId, INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
    }

    private boolean hasBlockingPendingDecision(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = ?
            """,
            Integer.class,
            matchId,
            userId,
            PENDING_STATUS
        );
        return count != null && count > 0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("無法序列化 BLOOM effect payload", ex);
        }
    }
}
