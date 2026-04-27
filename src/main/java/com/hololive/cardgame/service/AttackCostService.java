package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AttackCostService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AttackCostService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Integer> parseCost(String costCheerJsonText) {
        Map<String, Integer> cost = new LinkedHashMap<>();
        if (!StringUtils.hasText(costCheerJsonText)) {
            return cost;
        }
        try {
            JsonNode root = objectMapper.readTree(costCheerJsonText);
            if (root == null || !root.isObject()) {
                return cost;
            }
            root.fields().forEachRemaining(entry -> {
                String color = normalize(entry.getKey());
                int required = entry.getValue() == null ? 0 : entry.getValue().asInt(0);
                if (StringUtils.hasText(color) && required > 0) {
                    cost.put(color, required);
                }
            });
        } catch (Exception ignored) {
            return Map.of();
        }
        return cost;
    }

    public Map<String, Integer> applyReduction(Map<String, Integer> baseCost, Map<String, Integer> reduction) {
        Map<String, Integer> effectiveCost = new LinkedHashMap<>();
        if (baseCost == null || baseCost.isEmpty()) {
            return effectiveCost;
        }
        for (Map.Entry<String, Integer> entry : baseCost.entrySet()) {
            String color = normalize(entry.getKey());
            int required = entry.getValue() == null ? 0 : entry.getValue();
            if (!StringUtils.hasText(color) || required <= 0) {
                continue;
            }
            int reducedBy = reduction == null ? 0 : Math.max(0, reduction.getOrDefault(color, 0));
            int effectiveRequired = Math.max(required - reducedBy, 0);
            if (effectiveRequired > 0) {
                effectiveCost.put(color, effectiveRequired);
            }
        }
        return effectiveCost;
    }

    public AttackCostPaymentResult resolvePayment(AttackCostPaymentContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack cost payment 缺少必要上下文");
        }
        Map<String, Integer> requiredCost = applyReduction(context.baseCost(), context.costReduction());
        Map<String, Integer> normalizedRequired = normalizeCost(requiredCost);
        int totalRequired = normalizedRequired.values().stream().mapToInt(Integer::intValue).sum();
        if (totalRequired <= 0) {
            return new AttackCostPaymentResult(
                context.baseCost(),
                context.costReduction(),
                normalizedRequired,
                0,
                Map.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                context.consume()
            );
        }

        List<Map<String, Object>> attachedRows = jdbcTemplate.queryForList(
            """
            SELECT mhc.id AS cheer_row_id,
                   mhc.match_card_id,
                   mhc.cheer_card_id,
                   cc.color
            FROM match_holomem_cheers mhc
            JOIN cheer_cards cc ON cc.card_id = mhc.cheer_card_id
            WHERE mhc.match_holomem_id = ?
            ORDER BY mhc.id
            """,
            context.attackerHolomemId()
        );
        if (attachedRows.isEmpty()) {
            throw new IllegalStateException("藝能費用不足：未附加任何 Cheer");
        }

        List<Map<String, Object>> remaining = new ArrayList<>(attachedRows);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : normalizedRequired.entrySet()) {
            String color = entry.getKey();
            if ("COLORLESS".equals(color)) {
                continue;
            }
            int required = entry.getValue();
            for (int i = 0; i < required; i++) {
                int idx = findFirstCheerIndexByColor(remaining, color);
                if (idx < 0) {
                    throw new IllegalStateException("藝能費用不足：需要 " + color + " Cheer x" + required);
                }
                selected.add(remaining.remove(idx));
            }
        }

        int colorlessRequired = normalizedRequired.getOrDefault("COLORLESS", 0);
        for (int i = 0; i < colorlessRequired; i++) {
            if (remaining.isEmpty()) {
                throw new IllegalStateException("藝能費用不足：需要無色 Cheer x" + colorlessRequired);
            }
            selected.add(remaining.remove(0));
        }

        Map<String, Integer> paid = new LinkedHashMap<>();
        List<String> paidCheerCardIds = new ArrayList<>();
        List<Long> paidCheerCardInstanceIds = new ArrayList<>();
        List<String> paidColors = new ArrayList<>();
        for (Map<String, Object> row : selected) {
            String cheerCardId = asString(row.get("cheer_card_id"));
            Long cheerCardInstanceId = asLong(row.get("match_card_id"));
            String color = normalize(row.get("color"));
            if (!StringUtils.hasText(cheerCardId) || !StringUtils.hasText(color)) {
                continue;
            }
            paid.put(color, paid.getOrDefault(color, 0) + 1);
            paidCheerCardIds.add(cheerCardId);
            if (cheerCardInstanceId != null) {
                paidCheerCardInstanceIds.add(cheerCardInstanceId);
            }
            paidColors.add(color);
        }

        return new AttackCostPaymentResult(
            context.baseCost(),
            context.costReduction(),
            normalizedRequired,
            totalRequired,
            paid,
            selected.size(),
            paidCheerCardIds,
            paidCheerCardInstanceIds,
            paidColors,
            context.consume()
        );
    }

    private Map<String, Integer> normalizeCost(Map<String, Integer> cost) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        if (cost == null || cost.isEmpty()) {
            return normalized;
        }
        for (Map.Entry<String, Integer> entry : cost.entrySet()) {
            String color = normalize(entry.getKey());
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (StringUtils.hasText(color) && count > 0) {
                normalized.put(color, count);
            }
        }
        return normalized;
    }

    private int findFirstCheerIndexByColor(List<Map<String, Object>> rows, String color) {
        for (int i = 0; i < rows.size(); i++) {
            if (color.equals(normalize(rows.get(i).get("color")))) {
                return i;
            }
        }
        return -1;
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
