package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class FollowupTriggerConfirmPendingDecisionWriter {

    private static final String INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM = "TRIGGER_EFFECT_CONFIRM";
    private static final String PENDING_STATUS = "PENDING";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    FollowupTriggerConfirmPendingDecisionWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    FollowupInteractionDecision create(FollowupTriggerConfirmPendingDecisionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("trigger confirm pending decision 缺少必要上下文");
        }
        if (hasBlockingPendingDecision(input.matchId(), input.userId())) {
            throw new IllegalStateException("你有待處理的互動，請先完成確認");
        }
        Map<String, Object> additionalContext = input.additionalContext();
        int minSelect = 0;
        int maxSelect = 0;
        if (additionalContext != null && !additionalContext.isEmpty()) {
            minSelect = Math.max(asInt(additionalContext.get("minSelect")), 0);
            maxSelect = Math.max(asInt(additionalContext.get("maxSelect")), minSelect);
        }
        String sourceActionType = normalizeZone(input.sourceActionType());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
        context.put("sourceActionType", sourceActionType);
        context.put("title", input.title());
        context.put("message", input.message());
        context.put("cards", input.cards() == null ? List.of() : input.cards());
        context.put("turnNumber", input.turnNumber());
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
            input.matchId(),
            input.userId(),
            INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM,
            sourceActionType,
            input.sourceCardInstanceId(),
            input.sourceCardId(),
            input.effectType(),
            minSelect,
            maxSelect,
            PENDING_STATUS,
            toJson(context)
        );
        if (decisionId == null) {
            return null;
        }
        return new FollowupInteractionDecision(decisionId, INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("無法序列化效果確認內容", e);
        }
    }

    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
