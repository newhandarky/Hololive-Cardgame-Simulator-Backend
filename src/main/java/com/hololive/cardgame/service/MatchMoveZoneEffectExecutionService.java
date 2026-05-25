package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.HolomemMoveZoneAction;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchMoveZoneEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final GameActionExecutor gameActionExecutor;
    private final EffectTextParser effectTextParser;
    private final DiceConditionChecker diceConditionChecker;
    private final TargetHolomemResolver targetHolomemResolver;
    private final CurrentTurnResolver currentTurnResolver;
    private final ActionLockChecker actionLockChecker;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchMoveZoneEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        GameActionExecutor gameActionExecutor,
        EffectTextParser effectTextParser,
        DiceConditionChecker diceConditionChecker,
        TargetHolomemResolver targetHolomemResolver,
        CurrentTurnResolver currentTurnResolver,
        ActionLockChecker actionLockChecker,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.gameActionExecutor = gameActionExecutor;
        this.effectTextParser = effectTextParser;
        this.diceConditionChecker = diceConditionChecker;
        this.targetHolomemResolver = targetHolomemResolver;
        this.currentTurnResolver = currentTurnResolver;
        this.actionLockChecker = actionLockChecker;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

    /**
     * 執行區域移動效果（CENTER/BACK/COLLAB 等），含休息狀態調整。
     */
    Map<String, Object> executeMoveZoneEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.extractText(effectNode, "rawText", "rawEffect");
        if (!diceConditionChecker.shouldApply(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }

        Long targetHolomemId = targetHolomemResolver.resolve(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("MOVE_ZONE 找不到目標 Holomen");
        }
        Map<String, Object> holomem = findHolomem(matchId, targetHolomemId);
        if (holomem == null) {
            throw new IllegalStateException("MOVE_ZONE 結算失敗：找不到目標 Holomen");
        }

        Long targetOwnerUserId = MatchEffectValueHelper.asLong(holomem.get("owner_user_id"));
        String fromZone = MatchEffectValueHelper.normalize(holomem.get("zone"));
        String toZone = resolveMoveDestinationZone(effectNode);
        boolean restAfterMove = shouldRestAfterMove(effectNode);
        int currentTurn = currentTurnResolver.resolve(matchId);
        if (actionLockChecker.isActive(matchId, targetOwnerUserId, currentTurn, "MOVE_STAGE", fromZone, targetHolomemId)) {
            return executeNoOpEffect(effectType, effectNode, "目前效果限制：不可移動");
        }

        if (
            targetHolomemCardInstanceId == null
            && StringUtils.hasText(rawText)
            && rawText.contains("バックホロメン")
            && targetOwnerUserId != null
        ) {
            Long backTargetHolomemId = jdbcTemplate.query(
                """
                SELECT id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'BACK'
                ORDER BY id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                targetOwnerUserId
            );
            if (backTargetHolomemId != null) {
                targetHolomemId = backTargetHolomemId;
                holomem = findHolomem(matchId, targetHolomemId);
                if (holomem != null) {
                    targetOwnerUserId = MatchEffectValueHelper.asLong(holomem.get("owner_user_id"));
                    fromZone = MatchEffectValueHelper.normalize(holomem.get("zone"));
                }
            }
        }

        if (!"BACK".equals(toZone) && !"CENTER".equals(toZone) && !"COLLAB".equals(toZone)) {
            toZone = "BACK";
        }

        if (
            StringUtils.hasText(rawText)
            && rawText.contains("コラボホロメンがいないなら")
            && targetOwnerUserId != null
        ) {
            Integer collabCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'COLLAB'
                """,
                Integer.class,
                matchId,
                targetOwnerUserId
            );
            if (collabCount != null && collabCount > 0) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("effectType", effectType);
                summary.put("targetHolomemId", targetHolomemId);
                summary.put("fromZone", fromZone);
                summary.put("toZone", toZone);
                summary.put("moved", false);
                summary.put("reason", "條件不成立：目標玩家已有 COLLAB");
                return summary;
            }
        }

        if (toZone.equals(fromZone)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("fromZone", fromZone);
            summary.put("toZone", toZone);
            summary.put("moved", false);
            summary.put("reason", "目標已在同區域");
            return summary;
        }

        validateDestinationCapacity(matchId, targetOwnerUserId, toZone);

        Boolean restedAfterMove = null;
        EffectContext actionContext = new EffectContext(
            matchId,
            userId,
            currentTurn,
            effectType,
            holomemCardInstanceResolver.resolve(targetHolomemId),
            null
        );
        HolomemMoveZoneAction moveAction = new HolomemMoveZoneAction(targetHolomemId, fromZone, toZone, restAfterMove);
        List<ActionResult> actionResults = gameActionExecutor.execute(actionContext, List.of(moveAction));
        if (!actionResults.isEmpty() && actionResults.get(0).success()) {
            Object rested = actionResults.get(0).details().get("rested");
            if (rested instanceof Boolean value) {
                restedAfterMove = value;
            }
        } else {
            jdbcTemplate.update(
                """
                UPDATE match_holomems
                SET zone = ?,
                    is_rested = CASE WHEN ? THEN TRUE ELSE is_rested END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                """,
                toZone,
                restAfterMove,
                targetHolomemId,
                matchId
            );
            restedAfterMove = jdbcTemplate.query(
                "SELECT is_rested FROM match_holomems WHERE id = ? AND match_id = ?",
                rs -> rs.next() ? rs.getBoolean("is_rested") : null,
                targetHolomemId,
                matchId
            );
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
        summary.put("fromZone", fromZone);
        summary.put("toZone", toZone);
        summary.put("rested", restedAfterMove);
        summary.put("moved", true);
        return summary;
    }

    private Map<String, Object> findHolomem(Long matchId, Long targetHolomemId) {
        return jdbcTemplate.query(
            """
            SELECT owner_user_id, zone
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("owner_user_id", rs.getLong("owner_user_id"));
                row.put("zone", rs.getString("zone"));
                return row;
            },
            targetHolomemId,
            matchId
        );
    }

    private String resolveMoveDestinationZone(JsonNode effectNode) {
        String explicit = effectTextParser.normalizeEffectType(effectTextParser.extractText(effectNode, "toZone"));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String text = effectTextParser.extractText(effectNode, "rawText", "rawEffect");
        if (text.contains("バックポジション") || text.contains("バックに移動")) {
            return "BACK";
        }
        if (text.contains("センターポジション") || text.contains("センターに移動")) {
            return "CENTER";
        }
        if (text.contains("コラボポジション") || text.contains("コラボに移動") || text.contains("コラボホロメン")) {
            return "COLLAB";
        }
        return "BACK";
    }

    private boolean shouldRestAfterMove(JsonNode effectNode) {
        String text = effectTextParser.extractText(effectNode, "rawText", "rawEffect");
        return text.contains("お休み");
    }

    private void validateDestinationCapacity(Long matchId, Long targetOwnerUserId, String toZone) {
        if ("BACK".equals(toZone) && targetOwnerUserId != null) {
            Integer backCount = countHolomemsInZone(matchId, targetOwnerUserId, "BACK");
            if (backCount != null && backCount >= 5) {
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 BACK 已滿");
            }
        }
        if ("CENTER".equals(toZone) && targetOwnerUserId != null) {
            Integer centerCount = countHolomemsInZone(matchId, targetOwnerUserId, "CENTER");
            if (centerCount != null && centerCount > 0) {
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 CENTER 已有 Holomen");
            }
        }
        if ("COLLAB".equals(toZone) && targetOwnerUserId != null) {
            Integer collabCount = countHolomemsInZone(matchId, targetOwnerUserId, "COLLAB");
            if (collabCount != null && collabCount > 0) {
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 COLLAB 已有 Holomen");
            }
        }
    }

    private Integer countHolomemsInZone(Long matchId, Long ownerUserId, String zone) {
        return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            ownerUserId,
            zone
        );
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
    interface TargetHolomemResolver {
        Long resolve(
            Long matchId,
            Long userId,
            String targetType,
            Long targetHolomemCardInstanceId,
            boolean defaultOpponent
        );
    }

    @FunctionalInterface
    interface CurrentTurnResolver {
        int resolve(Long matchId);
    }

    @FunctionalInterface
    interface ActionLockChecker {
        boolean isActive(Long matchId, Long userId, int turnNumber, String action, String zone, Long holomemId);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
