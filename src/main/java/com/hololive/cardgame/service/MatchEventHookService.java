package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchEventHookService {

    private final JdbcTemplate jdbcTemplate;

    public MatchEventHookService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> onHolomemEnter(
        Long matchId,
        Long userId,
        String cardId,
        Long cardInstanceId,
        String stageZone
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", "ON_HOLOMEM_ENTER");
        result.put("matchId", matchId);
        result.put("userId", userId);
        result.put("cardId", cardId);
        result.put("cardInstanceId", cardInstanceId);
        result.put("stageZone", normalize(stageZone));

        Map<String, Object> summary = loadMemberTriggerSummary(cardId);
        if (summary == null) {
            result.put("hasPassive", false);
            result.put("detectedTriggers", List.of());
            return result;
        }
        result.putAll(summary);
        result.put("note", "目前僅建立事件鉤子與觸發辨識，效果執行將在後續 P2 擴充");
        return result;
    }

    public Map<String, Object> onHolomemBloom(
        Long matchId,
        Long userId,
        String cardId,
        Long cardInstanceId,
        Long previousTopCardInstanceId,
        String stageZone
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", "ON_HOLOMEM_BLOOM");
        result.put("matchId", matchId);
        result.put("userId", userId);
        result.put("cardId", cardId);
        result.put("cardInstanceId", cardInstanceId);
        result.put("previousTopCardInstanceId", previousTopCardInstanceId);
        result.put("stageZone", normalize(stageZone));

        Map<String, Object> summary = loadMemberTriggerSummary(cardId);
        if (summary == null) {
            result.put("hasPassive", false);
            result.put("detectedTriggers", List.of());
            return result;
        }
        result.putAll(summary);
        result.put("note", "目前僅建立事件鉤子與觸發辨識，效果執行將在後續 P2 擴充");
        return result;
    }

    private Map<String, Object> loadMemberTriggerSummary(String cardId) {
        Map<String, Object> row = jdbcTemplate.query(
            """
            SELECT passive_effect_json::text AS passive_text, trigger_condition
            FROM member_cards
            WHERE card_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("passive_text", rs.getString("passive_text"));
                value.put("trigger_condition", rs.getString("trigger_condition"));
                return value;
            },
            cardId
        );
        if (row == null) {
            return null;
        }

        String passiveText = asText(row.get("passive_text"));
        String triggerCondition = asText(row.get("trigger_condition"));
        List<String> detected = new ArrayList<>();
        if (contains(passiveText, "ギフト")) {
            detected.add("GIFT");
        }
        if (contains(passiveText, "ブルームエフェクト")) {
            detected.add("BLOOM");
        }
        if (contains(passiveText, "コラボエフェクト")) {
            detected.add("COLLAB");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hasPassive", StringUtils.hasText(passiveText));
        summary.put("triggerCondition", triggerCondition);
        summary.put("detectedTriggers", detected);
        return summary;
    }

    private boolean contains(String source, String target) {
        return StringUtils.hasText(source) && source.contains(target);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }
}
