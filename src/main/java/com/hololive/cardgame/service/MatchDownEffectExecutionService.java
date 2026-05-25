package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.ReduceLifeAction;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchDownEffectExecutionService {

    private static final Pattern DOWN_EXTRA_LIFE_PATTERN = Pattern.compile("ライフを\\s*(\\d+)\\s*つ?減ら");
    private static final Pattern DOWN_EXTRA_LIFE_MINUS_PATTERN = Pattern.compile("ライフ\\s*[ー\\-−]\\s*(\\d+)");

    private final JdbcTemplate jdbcTemplate;
    private final GameActionExecutor gameActionExecutor;
    private final EffectTextParser effectTextParser;
    private final DiceConditionChecker diceConditionChecker;
    private final OpponentUserResolver opponentUserResolver;
    private final CurrentTurnResolver currentTurnResolver;
    private final AttachedCardArchiver cheerCardArchiver;
    private final AttachedCardArchiver supportCardArchiver;
    private final AttachedCardArchiver holomemStackArchiver;
    private final DownEventExecutor downEventExecutor;
    private final LifeLossResolver lifeLossResolver;

    MatchDownEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        GameActionExecutor gameActionExecutor,
        EffectTextParser effectTextParser,
        DiceConditionChecker diceConditionChecker,
        OpponentUserResolver opponentUserResolver,
        CurrentTurnResolver currentTurnResolver,
        AttachedCardArchiver cheerCardArchiver,
        AttachedCardArchiver supportCardArchiver,
        AttachedCardArchiver holomemStackArchiver,
        DownEventExecutor downEventExecutor,
        LifeLossResolver lifeLossResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.gameActionExecutor = gameActionExecutor;
        this.effectTextParser = effectTextParser;
        this.diceConditionChecker = diceConditionChecker;
        this.opponentUserResolver = opponentUserResolver;
        this.currentTurnResolver = currentTurnResolver;
        this.cheerCardArchiver = cheerCardArchiver;
        this.supportCardArchiver = supportCardArchiver;
        this.holomemStackArchiver = holomemStackArchiver;
        this.downEventExecutor = downEventExecutor;
        this.lifeLossResolver = lifeLossResolver;
    }

    /**
     * 執行擊倒但不扣生命的效果分支。
     */
    Map<String, Object> executeDownNoLifeEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        Long opponentUserId = opponentUserResolver.resolve(matchId, userId);
        if (opponentUserId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到對手");
        }
        boolean requireDamaged40 = rawText.contains("HPが40以上減っている");

        Map<String, Object> target = jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.damage_taken
            FROM match_holomems h
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'BACK'
              AND (? = FALSE OR COALESCE(h.damage_taken, 0) >= 40)
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("holomem_id", rs.getLong("holomem_id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("damage_taken", rs.getInt("damage_taken"));
                return row;
            },
            matchId,
            opponentUserId,
            requireDamaged40
        );
        if (target == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有符合條件的 BACK 目標可 Down");
        }

        Long targetHolomemId = MatchEffectValueHelper.asLong(target.get("holomem_id"));
        Long targetCardInstanceId = MatchEffectValueHelper.asLong(target.get("match_card_id"));
        if (targetHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "目標 Holomem 資料不足");
        }
        String targetCardId = targetCardInstanceId == null
            ? null
            : jdbcTemplate.query(
                """
                SELECT card_id
                FROM match_cards
                WHERE match_id = ?
                  AND id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("card_id") : null,
                matchId,
                targetCardInstanceId
            );

        List<Long> archivedCheerCardInstanceIds = cheerCardArchiver.archive(matchId, targetHolomemId, opponentUserId);
        List<Long> archivedSupportCardInstanceIds = supportCardArchiver.archive(matchId, targetHolomemId, opponentUserId);
        List<Long> archivedHolomemCardInstanceIds = holomemStackArchiver.archive(matchId, targetHolomemId, opponentUserId);

        jdbcTemplate.update(
            "DELETE FROM match_holomems WHERE id = ? AND match_id = ?",
            targetHolomemId,
            matchId
        );
        if (archivedHolomemCardInstanceIds.isEmpty() && targetCardInstanceId != null) {
            int archiveOrder = nextZoneOrder(matchId, opponentUserId, "ARCHIVE");
            jdbcTemplate.update(
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
                targetCardInstanceId,
                matchId,
                opponentUserId
            );
        }

        int currentTurn = currentTurnResolver.resolve(matchId);
        Map<String, Object> downEventSummary = downEventExecutor.execute(
            matchId,
            userId,
            opponentUserId,
            targetCardId,
            currentTurn,
            true,
            "BACK"
        );
        List<Long> lostLifeCardInstanceIds = extractLostLifeCardInstanceIds(downEventSummary);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", targetCardInstanceId);
        summary.put("targetOwnerUserId", opponentUserId);
        summary.put("downed", true);
        summary.put("lifeReduced", !lostLifeCardInstanceIds.isEmpty());
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceIds.isEmpty() ? null : lostLifeCardInstanceIds.get(0));
        summary.put("lostLifeCardInstanceIds", lostLifeCardInstanceIds);
        summary.put("archivedCheerCardInstanceIds", archivedCheerCardInstanceIds);
        summary.put("archivedSupportCardInstanceIds", archivedSupportCardInstanceIds);
        summary.put("archivedHolomemCardInstanceIds", archivedHolomemCardInstanceIds);
        summary.put("downEvent", downEventSummary);
        return summary;
    }

    /**
     * 執行擊倒並額外扣生命的效果分支。
     */
    Map<String, Object> executeDownExtraLifeEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        Map<String, Object> summary = executeDownNoLifeEffect(matchId, userId, effectType, effectNode);
        if (!MatchEffectValueHelper.toBoolean(summary.get("applied")) || !MatchEffectValueHelper.toBoolean(summary.get("downed"))) {
            return summary;
        }
        Long targetOwnerUserId = MatchEffectValueHelper.asLong(summary.get("targetOwnerUserId"));
        Long targetHolomemCardInstanceId = MatchEffectValueHelper.asLong(summary.get("targetHolomemCardInstanceId"));
        if ((targetOwnerUserId == null || targetOwnerUserId <= 0) && targetHolomemCardInstanceId != null && targetHolomemCardInstanceId > 0) {
            targetOwnerUserId = jdbcTemplate.query(
                """
                SELECT owner_user_id
                FROM match_cards
                WHERE match_id = ?
                  AND id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("owner_user_id") : null,
                matchId,
                targetHolomemCardInstanceId
            );
        }
        if (targetOwnerUserId == null || targetOwnerUserId <= 0) {
            return summary;
        }

        int requestedLifeLoss = resolveDownExtraLifeCount(effectNode);
        List<Long> lostLifeCardInstanceIds = new ArrayList<>();
        if (requestedLifeLoss > 0) {
            EffectContext actionContext = new EffectContext(
                matchId,
                userId,
                currentTurnResolver.resolve(matchId),
                effectType,
                targetHolomemCardInstanceId,
                null
            );
            ReduceLifeAction reduceLifeAction = new ReduceLifeAction(targetOwnerUserId, requestedLifeLoss, "DOWN_EXTRA_LIFE");
            List<ActionResult> actionResults = gameActionExecutor.execute(actionContext, List.of(reduceLifeAction));
            if (!actionResults.isEmpty() && actionResults.get(0).success()) {
                Object ids = actionResults.get(0).details().get("lifeCardInstanceIds");
                if (ids instanceof List<?> list) {
                    for (Object id : list) {
                        Long parsedId = MatchEffectValueHelper.asLong(id);
                        if (parsedId != null) {
                            lostLifeCardInstanceIds.add(parsedId);
                        }
                    }
                }
            }
        }
        if (lostLifeCardInstanceIds.isEmpty()) {
            for (int index = 0; index < requestedLifeLoss; index += 1) {
                Long lostLifeCardInstanceId = lifeLossResolver.loseLifeOnce(matchId, targetOwnerUserId);
                if (lostLifeCardInstanceId == null) {
                    break;
                }
                lostLifeCardInstanceIds.add(lostLifeCardInstanceId);
            }
        }

        if (!lostLifeCardInstanceIds.isEmpty()) {
            summary.put("lifeReduced", true);
            summary.put("lostLifeCardInstanceId", lostLifeCardInstanceIds.get(0));
            summary.put("lostLifeCardInstanceIds", lostLifeCardInstanceIds);
        }
        summary.put("extraLifeLossRequested", requestedLifeLoss);
        summary.put("extraLifeLossApplied", lostLifeCardInstanceIds.size());
        return summary;
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

    private List<Long> extractLostLifeCardInstanceIds(Map<String, Object> effectSummary) {
        if (effectSummary == null || effectSummary.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        Long single = MatchEffectValueHelper.asLong(effectSummary.get("lostLifeCardInstanceId"));
        if (single != null && single > 0) {
            ids.add(single);
        }
        Object listObject = effectSummary.get("lostLifeCardInstanceIds");
        if (listObject instanceof List<?> list) {
            for (Object value : list) {
                Long id = MatchEffectValueHelper.asLong(value);
                if (id != null && id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        Object actionListObject = effectSummary.get("lifeCardInstanceIds");
        if (actionListObject instanceof List<?> list) {
            for (Object value : list) {
                Long id = MatchEffectValueHelper.asLong(value);
                if (id != null && id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private int resolveDownExtraLifeCount(JsonNode effectNode) {
        int fromField = effectTextParser.extractInt(effectNode, 0, "extraLifeLoss", "lifeLoss", "value", "amount");
        if (fromField > 0) {
            return fromField;
        }
        String merged = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        int byPattern = effectTextParser.extractByPattern(merged, DOWN_EXTRA_LIFE_PATTERN);
        if (byPattern > 0) {
            return byPattern;
        }
        int minusPattern = effectTextParser.extractByPattern(merged, DOWN_EXTRA_LIFE_MINUS_PATTERN);
        if (minusPattern > 0) {
            return minusPattern;
        }
        if (StringUtils.hasText(merged) && merged.contains("ライフ")) {
            return 1;
        }
        return 0;
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

    @FunctionalInterface
    interface OpponentUserResolver {
        Long resolve(Long matchId, Long userId);
    }

    @FunctionalInterface
    interface CurrentTurnResolver {
        int resolve(Long matchId);
    }

    @FunctionalInterface
    interface AttachedCardArchiver {
        List<Long> archive(Long matchId, Long matchHolomemId, Long ownerUserId);
    }

    @FunctionalInterface
    interface DownEventExecutor {
        Map<String, Object> execute(
            Long matchId,
            Long userId,
            Long downedOwnerUserId,
            String downedCardId,
            int turnNumber,
            boolean applyDefaultLifeLoss,
            String downedStageZone
        );
    }

    @FunctionalInterface
    interface LifeLossResolver {
        Long loseLifeOnce(Long matchId, Long ownerUserId);
    }
}
