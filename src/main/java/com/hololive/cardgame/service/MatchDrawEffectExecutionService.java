package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.AtomicAction;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.EffectResolver;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchDrawEffectExecutionService {

    private static final Pattern DRAW_COUNT_PATTERN = Pattern.compile("デッキを\\s*(\\d+)\\s*枚引く");
    private static final Pattern DRAW_COUNT_FALLBACK_PATTERN = Pattern.compile("(\\d+)\\s*枚引く");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectResolver effectResolver;
    private final GameActionExecutor gameActionExecutor;
    private final EffectTextParser effectTextParser;
    private final DiceConditionChecker diceConditionChecker;

    MatchDrawEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        EffectResolver effectResolver,
        GameActionExecutor gameActionExecutor,
        EffectTextParser effectTextParser,
        DiceConditionChecker diceConditionChecker
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.effectResolver = effectResolver;
        this.gameActionExecutor = gameActionExecutor;
        this.effectTextParser = effectTextParser;
        this.diceConditionChecker = diceConditionChecker;
    }

    /**
     * 執行抽牌效果，優先走 Action Pipeline，失敗時回退到既有 SQL 流程。
     */
    Map<String, Object> executeDrawEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        int requestedCount = resolveDrawCount(effectNode);
        int drawCount = Math.max(requestedCount, 1);

        List<Long> drawnCardInstanceIds = new ArrayList<>();
        // P1-3: 以 EffectResolver + GameActionExecutor 執行 DRAW，舊 SQL 作為安全 fallback。
        try {
            ObjectNode pipelineNode = objectMapper.createObjectNode();
            pipelineNode.put("drawCount", drawCount);
            pipelineNode.put("fromZone", "DECK");
            pipelineNode.put("toZone", "HAND");
            EffectContext context = new EffectContext(
                matchId,
                userId,
                resolveCurrentTurnNumber(matchId),
                "SUPPORT_EFFECT",
                null,
                null
            );
            List<AtomicAction> actions = effectResolver.resolve(context, effectType, pipelineNode);
            if (!actions.isEmpty()) {
                List<ActionResult> results = gameActionExecutor.execute(context, actions);
                if (!results.isEmpty() && results.get(0).success()) {
                    Object movedCards = results.get(0).details().get("cardInstanceIds");
                    if (movedCards instanceof List<?> list) {
                        for (Object id : list) {
                            if (id instanceof Number n) {
                                drawnCardInstanceIds.add(n.longValue());
                            } else if (id instanceof String s) {
                                try {
                                    drawnCardInstanceIds.add(Long.parseLong(s));
                                } catch (NumberFormatException ignored) {
                                    // ignore invalid id payload
                                }
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fallback below
        }
        if (drawnCardInstanceIds.isEmpty()) {
            int nextHandOrder = nextZoneOrder(matchId, userId, "HAND");
            for (int i = 0; i < drawCount; i++) {
                Long deckCardInstanceId = jdbcTemplate.query(
                    """
                    SELECT id
                    FROM match_cards
                    WHERE match_id = ?
                      AND owner_user_id = ?
                      AND zone = 'DECK'
                    ORDER BY order_index NULLS LAST, id
                    LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    matchId,
                    userId
                );
                if (deckCardInstanceId == null) {
                    break;
                }
                jdbcTemplate.update(
                    """
                    UPDATE match_cards
                    SET zone = 'HAND',
                        order_index = ?,
                        is_face_down = FALSE,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                      AND match_id = ?
                      AND owner_user_id = ?
                      AND zone = 'DECK'
                    """,
                    nextHandOrder++,
                    deckCardInstanceId,
                    matchId,
                    userId
                );
                drawnCardInstanceIds.add(deckCardInstanceId);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("drawRequested", drawCount);
        summary.put("drawApplied", drawnCardInstanceIds.size());
        summary.put("drawnCardInstanceIds", drawnCardInstanceIds);
        return summary;
    }

    private int resolveDrawCount(JsonNode effectNode) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        int exact = effectTextParser.extractByPattern(text, DRAW_COUNT_PATTERN);
        if (exact > 0) {
            return exact;
        }
        int fallback = effectTextParser.extractByPattern(text, DRAW_COUNT_FALLBACK_PATTERN);
        if (fallback > 0) {
            return fallback;
        }
        return text.contains("引く") ? 1 : 0;
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

    private int resolveCurrentTurnNumber(Long matchId) {
        Integer turn = jdbcTemplate.query(
            "SELECT turn_number FROM matches WHERE id = ?",
            rs -> rs.next() ? rs.getInt("turn_number") : null,
            matchId
        );
        if (turn == null || turn <= 0) {
            return 1;
        }
        return turn;
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
