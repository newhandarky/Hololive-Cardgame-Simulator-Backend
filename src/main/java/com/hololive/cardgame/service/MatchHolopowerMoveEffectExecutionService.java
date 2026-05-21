package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchHolopowerMoveEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver;
    private final DiceConditionChecker diceConditionChecker;

    MatchHolopowerMoveEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        MatchCardSelectionRequestResolver cardSelectionRequestResolver,
        DiceConditionChecker diceConditionChecker
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.cardSelectionRequestResolver = cardSelectionRequestResolver;
        this.diceConditionChecker = diceConditionChecker;
    }

    /**
     * 執行移入 Holopower 的效果（通常來自 Deck/Archive/Hand）。
     */
    Map<String, Object> executeMoveToHolopowerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        String sourceZone = resolveMoveToHolopowerSourceZone(effectNode, rawText);
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "ホロパワー", 1);
        int moveCount = Math.max(requestedCount, 1);

        List<Long> movedCardInstanceIds = new ArrayList<>();
        for (int i = 0; i < moveCount; i++) {
            Long sourceCardInstanceId = jdbcTemplate.query(
                """
                SELECT id
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = ?
                ORDER BY order_index NULLS LAST, id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                userId,
                sourceZone
            );
            if (sourceCardInstanceId == null) {
                break;
            }
            int nextOrder = nextZoneOrder(matchId, userId, "HOLOPOWER");
            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'HOLOPOWER',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = ?
                """,
                nextOrder,
                sourceCardInstanceId,
                matchId,
                userId,
                sourceZone
            );
            if (moved == 1) {
                movedCardInstanceIds.add(sourceCardInstanceId);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("sourceZone", sourceZone);
        summary.put("moveRequested", moveCount);
        summary.put("moveApplied", movedCardInstanceIds.size());
        summary.put("movedCardInstanceIds", movedCardInstanceIds);
        return summary;
    }

    private String resolveMoveToHolopowerSourceZone(JsonNode effectNode, String rawText) {
        String explicit = effectTextParser.normalizeEffectType(
            MatchEffectValueHelper.readText(effectNode, "holopowerSourceZone", "moveSourceZone", "sourceZone")
        );
        if ("DECK".equals(explicit) || "ARCHIVE".equals(explicit) || "HAND".equals(explicit)) {
            return explicit;
        }
        String text = effectTextParser.normalizeDigits(rawText);
        if (text.contains("手札") && text.contains("ホロパワーにする")) {
            return "HAND";
        }
        if (text.contains("アーカイブ") && text.contains("ホロパワーにする")) {
            return "ARCHIVE";
        }
        return "DECK";
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

    private Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        summary.put("rawText", effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        return summary;
    }

    @FunctionalInterface
    interface DiceConditionChecker {
        boolean shouldApply(String rawText, JsonNode effectNode, String effectType);
    }
}
