package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

class PendingDecisionStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    PendingDecisionStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    PendingDecision loadForUpdate(Long matchId, Long userId, Long decisionId) {
        return jdbcTemplate.query(
            """
            SELECT id,
                   decision_type,
                   source_action_type,
                   source_card_instance_id,
                   source_card_id,
                   effect_type,
                   min_select,
                   max_select,
                   context_json::text AS context_text
            FROM match_pending_decisions
            WHERE id = ?
              AND match_id = ?
              AND user_id = ?
              AND status = ?
            FOR UPDATE
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return mapPendingDecision(rs);
            },
            decisionId,
            matchId,
            userId,
            PendingDecisionReader.PENDING_STATUS
        );
    }

    void markResolved(Long decisionId) {
        int updated = jdbcTemplate.update(
            """
            UPDATE match_pending_decisions
            SET status = 'RESOLVED',
                resolved_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND status = ?
            """,
            decisionId,
            PendingDecisionReader.PENDING_STATUS
        );
        if (updated != 1) {
            throw new IllegalStateException("決策已失效，請重新整理對戰狀態");
        }
    }

    private PendingDecision mapPendingDecision(ResultSet rs) throws SQLException {
        JsonNode contextNode = parseJson(rs.getString("context_text"));
        int minSelect = Math.max(rs.getInt("min_select"), 0);
        int maxSelect = Math.max(rs.getInt("max_select"), minSelect);
        return new PendingDecision(
            rs.getLong("id"),
            MatchEffectValueHelper.normalize(rs.getString("decision_type")),
            MatchEffectValueHelper.normalize(rs.getString("source_action_type")),
            rs.getLong("source_card_instance_id"),
            rs.getString("source_card_id"),
            MatchEffectValueHelper.normalize(rs.getString("effect_type")),
            minSelect,
            maxSelect,
            MatchEffectValueHelper.readLong(contextNode, "targetHolomemCardInstanceId"),
            MatchEffectValueHelper.readText(contextNode, "targetType"),
            MatchEffectValueHelper.readText(contextNode, "effectJson"),
            extractJsonLongList(contextNode, "candidateCardInstanceIds"),
            Boolean.TRUE.equals(MatchEffectValueHelper.readBoolean(contextNode, "limited")),
            contextNode
        );
    }

    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.nullNode();
        }
    }

    private List<Long> extractJsonLongList(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return List.of();
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode item : value) {
            Long id = null;
            if (item != null && item.isNumber()) {
                id = item.longValue();
            } else if (item != null && item.isTextual()) {
                try {
                    id = Long.parseLong(item.asText().trim());
                } catch (NumberFormatException ignored) {
                    id = null;
                }
            }
            if (id == null || id <= 0 || result.contains(id)) {
                continue;
            }
            result.add(id);
        }
        return result;
    }
}
