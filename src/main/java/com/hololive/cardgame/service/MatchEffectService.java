package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchEffectService {

    private static final Pattern SPECIAL_DAMAGE_PATTERN = Pattern.compile("特殊ダメージ\\s*(\\d+)");
    private static final Pattern DAMAGE_PATTERN = Pattern.compile("ダメージ\\s*(\\d+)");
    private static final Pattern DRAW_COUNT_PATTERN = Pattern.compile("デッキを\\s*(\\d+)\\s*枚引く");
    private static final Pattern DRAW_COUNT_FALLBACK_PATTERN = Pattern.compile("(\\d+)\\s*枚引く");
    private static final Pattern HEAL_PATTERN = Pattern.compile("HP\\s*(\\d+)\\s*回復");
    private static final Pattern CHEER_COUNT_PATTERN = Pattern.compile("エール\\s*(\\d+)\\s*枚");
    private static final Pattern SEARCH_RANGE_PATTERN = Pattern.compile("(\\d+)\\s*[~〜～]\\s*(\\d+)\\s*枚");
    private static final Pattern SEARCH_COUNT_PATTERN = Pattern.compile("(\\d+)\\s*枚");
    private static final Pattern TAG_PATTERN = Pattern.compile("#[\\p{L}\\p{N}_]+");
    private static final Pattern ARTS_MODIFIER_PATTERN = Pattern.compile("アーツ\\s*([+＋\\-−]\\s*\\d+)");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MatchEffectService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> applySupportEffect(
        Long matchId,
        Long userId,
        String effectType,
        String effectJson,
        String targetType,
        List<Long> selectedCardInstanceIds,
        Long targetHolomemCardInstanceId
    ) {
        JsonNode effectNode = parseEffectJson(effectJson);
        List<String> effectTypes = resolveEffectTypes(effectType, effectNode);
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();

        for (String type : effectTypes) {
            switch (type) {
                case "DRAW" -> executed.add(executeDrawEffect(matchId, userId, type, effectNode));
                case "SEARCH" -> executed.add(
                    executeSearchEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
                );
                case "ADD_CHEER" -> executed.add(
                    executeAddCheerEffect(
                        matchId,
                        userId,
                        type,
                        effectNode,
                        targetType,
                        targetHolomemCardInstanceId
                    )
                );
                case "DAMAGE" -> executed.add(
                    executeDamageEffect(
                        matchId,
                        userId,
                        type,
                        effectNode,
                        targetType,
                        targetHolomemCardInstanceId
                    )
                );
                case "HEAL" -> executed.add(
                    executeHealEffect(
                        matchId,
                        userId,
                        type,
                        effectNode,
                        targetType,
                        targetHolomemCardInstanceId
                    )
                );
                case "REMOVE_CHEER" -> executed.add(
                    executeRemoveCheerEffect(
                        matchId,
                        userId,
                        type,
                        effectNode,
                        targetType,
                        targetHolomemCardInstanceId
                    )
                );
                case "MOVE_ZONE" -> executed.add(
                    executeMoveZoneEffect(
                        matchId,
                        userId,
                        type,
                        effectNode,
                        targetType,
                        targetHolomemCardInstanceId
                    )
                );
                case "BUFF", "DEBUFF" -> executed.add(
                    executeBuffDebuffEffect(
                        matchId,
                        userId,
                        type,
                        effectNode,
                        targetType
                    )
                );
                case "UNIMPLEMENTED" -> executed.add(
                    executeNoOpEffect(type, effectNode, "尚未落地，先保留 action 並不中斷流程")
                );
                default -> unsupported.add(type);
            }
        }

        if (executed.isEmpty()) {
            String unknown = unsupported.isEmpty() ? "(empty)" : String.join(", ", unsupported);
            throw new IllegalStateException("SUPPORT 效果尚未實作：" + unknown);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requestedEffects", effectTypes);
        summary.put("executedEffects", executed);
        summary.put("unsupportedEffects", unsupported);
        return summary;
    }

    private Map<String, Object> executeDrawEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = resolveDrawCount(effectNode);
        int drawCount = Math.max(requestedCount, 1);

        List<Long> drawnCardInstanceIds = new ArrayList<>();
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

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("drawRequested", drawCount);
        summary.put("drawApplied", drawnCardInstanceIds.size());
        summary.put("drawnCardInstanceIds", drawnCardInstanceIds);
        return summary;
    }

    private Map<String, Object> executeSearchEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        int requestedCount = resolveSearchCount(effectNode);
        int searchCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);

        List<Map<String, Object>> candidates = loadSearchCandidates(
            matchId,
            userId,
            criteria.cardType(),
            criteria.levelType(),
            criteria.tag(),
            criteria.nameContains()
        );

        List<Map<String, Object>> selected = selectSearchCards(candidates, selectedCardInstanceIds, searchCount);
        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        int nextHandOrder = nextZoneOrder(matchId, userId, "HAND");
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            int updated = jdbcTemplate.update(
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
                cardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            movedCardInstanceIds.add(cardInstanceId);
            movedCardIds.add(cardId);
        }

        Map<String, Object> criteriaSummary = new LinkedHashMap<>();
        criteriaSummary.put("cardType", criteria.cardType());
        criteriaSummary.put("levelType", criteria.levelType());
        criteriaSummary.put("tag", criteria.tag());
        criteriaSummary.put("nameContains", criteria.nameContains());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("searchRequested", searchCount);
        summary.put("candidateCount", candidates.size());
        summary.put("searchApplied", movedCardInstanceIds.size());
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("searchedCardInstanceIds", movedCardInstanceIds);
        summary.put("searchedCardIds", movedCardIds);
        summary.put("criteria", criteriaSummary);
        return summary;
    }

    private Map<String, Object> executeAddCheerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            false
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("ADD_CHEER 需要指定可用的我方 Holomen");
        }
        int requestedCount = resolveCheerCount(effectNode, 1);
        int attachCount = Math.max(requestedCount, 1);

        List<Long> attachedCardInstanceIds = new ArrayList<>();
        List<String> sourceZones = new ArrayList<>();
        for (int i = 0; i < attachCount; i++) {
            Map<String, Object> source = findAttachableCheerCard(matchId, userId);
            if (source == null) {
                break;
            }
            Long cardInstanceId = asLong(source.get("id"));
            String sourceZone = normalize(source.get("zone"));
            String cardId = asText(source.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }

            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'STAGE',
                    order_index = NULL,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone IN ('CHEER_DECK','ARCHIVE','HAND')
                """,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                continue;
            }

            jdbcTemplate.update(
                """
                INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                VALUES (?, ?, FALSE)
                """,
                targetHolomemId,
                cardId
            );
            attachedCardInstanceIds.add(cardInstanceId);
            sourceZones.add(sourceZone);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("attachRequested", attachCount);
        summary.put("attachApplied", attachedCardInstanceIds.size());
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("attachedCheerCardInstanceIds", attachedCardInstanceIds);
        summary.put("sourceZones", sourceZones);
        return summary;
    }

    private Map<String, Object> executeDamageEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("DAMAGE 找不到可攻擊的對手 Holomen");
        }
        Long targetOwnerUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("DAMAGE 結算失敗：找不到目標擁有者");
        }

        int baseDamage = resolveDamageValue(effectNode);
        if (baseDamage <= 0) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("damageRequested", 0);
            summary.put("damageApplied", 0);
            summary.put("baseDamage", 0);
            summary.put("damageModifierApplied", 0);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("downed", false);
            summary.put("lifeReduced", false);
            summary.put("reason", "無可用傷害數值");
            return summary;
        }
        int currentTurn = resolveCurrentTurnNumber(matchId);
        int damageModifier = resolveActiveDamageModifier(matchId, userId, currentTurn);
        int damage = Math.max(baseDamage + damageModifier, 0);
        if (damage <= 0) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("damageRequested", baseDamage);
            summary.put("damageApplied", 0);
            summary.put("baseDamage", baseDamage);
            summary.put("damageModifierApplied", damageModifier);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("downed", false);
            summary.put("lifeReduced", false);
            summary.put("reason", "修正後傷害小於等於 0");
            return summary;
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = COALESCE(damage_taken, 0) + ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            damage,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );

        Map<String, Object> holomemState = jdbcTemplate.query(
            """
            SELECT id, match_card_id, card_id, zone, damage_taken
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                row.put("damage_taken", rs.getInt("damage_taken"));
                return row;
            },
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );
        if (holomemState == null) {
            throw new IllegalStateException("DAMAGE 結算失敗：找不到目標 Holomen");
        }

        String targetCardId = asText(holomemState.get("card_id"));
        int hp = jdbcTemplate.query(
            "SELECT hp FROM member_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getInt("hp") : 0,
            targetCardId
        );
        int damageTaken = asInt(holomemState.get("damage_taken"));

        boolean downed = hp > 0 && damageTaken >= hp;
        boolean lifeReduced = false;
        Long lostLifeCardInstanceId = null;
        List<Long> archivedCheerCardInstanceIds = new ArrayList<>();
        if (downed) {
            Long targetCardInstanceId = asLong(holomemState.get("match_card_id"));
            String targetZone = normalize(holomemState.get("zone"));
            archivedCheerCardInstanceIds = archiveAttachedCheerCards(matchId, targetHolomemId, targetOwnerUserId);

            jdbcTemplate.update(
                "DELETE FROM match_holomems WHERE id = ? AND match_id = ?",
                targetHolomemId,
                matchId
            );
            if (targetCardInstanceId != null) {
                int archiveOrder = nextZoneOrder(matchId, targetOwnerUserId, "ARCHIVE");
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
                    targetOwnerUserId
                );
            }

            boolean suppressLifeLoss = isDownWithoutLifeLoss(effectNode);
            if (!suppressLifeLoss && "CENTER".equals(targetZone)) {
                lostLifeCardInstanceId = loseLifeOnce(matchId, targetOwnerUserId);
                lifeReduced = lostLifeCardInstanceId != null;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("damageRequested", baseDamage);
        summary.put("damageApplied", damage);
        summary.put("baseDamage", baseDamage);
        summary.put("damageModifierApplied", damageModifier);
        summary.put("targetHp", hp);
        summary.put("targetDamageTaken", damageTaken);
        summary.put("downed", downed);
        summary.put("archivedCheerCardInstanceIds", archivedCheerCardInstanceIds);
        summary.put("lifeReduced", lifeReduced);
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceId);
        return summary;
    }

    private Map<String, Object> executeHealEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            false
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("HEAL 找不到可回復的 Holomen");
        }
        Long targetOwnerUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("HEAL 結算失敗：找不到目標擁有者");
        }

        int healRequested = resolveHealValue(effectNode);
        if (healRequested <= 0) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("healRequested", 0);
            summary.put("healApplied", 0);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("reason", "無可用回復數值");
            return summary;
        }

        Integer beforeDamage = jdbcTemplate.query(
            """
            SELECT damage_taken
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            rs -> rs.next() ? rs.getInt("damage_taken") : null,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );
        if (beforeDamage == null) {
            throw new IllegalStateException("HEAL 結算失敗：找不到目標 Holomen");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = GREATEST(COALESCE(damage_taken, 0) - ?, 0),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            healRequested,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );

        int afterDamage = jdbcTemplate.query(
            "SELECT COALESCE(damage_taken, 0) FROM match_holomems WHERE id = ?",
            rs -> rs.next() ? rs.getInt(1) : 0,
            targetHolomemId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("healRequested", healRequested);
        summary.put("healApplied", Math.max(beforeDamage - afterDamage, 0));
        summary.put("damageBefore", beforeDamage);
        summary.put("damageAfter", afterDamage);
        return summary;
    }

    private Map<String, Object> executeRemoveCheerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("REMOVE_CHEER 找不到目標 Holomen");
        }
        Long targetOwnerUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("REMOVE_CHEER 結算失敗：找不到目標擁有者");
        }

        int removeRequested = resolveCheerCount(effectNode, 1);
        int removeCount = Math.max(removeRequested, 1);
        List<Map<String, Object>> cheerRows = jdbcTemplate.queryForList(
            """
            SELECT id, cheer_card_id
            FROM match_holomem_cheers
            WHERE match_holomem_id = ?
            ORDER BY id
            LIMIT ?
            """,
            targetHolomemId,
            removeCount
        );

        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> removedCheerCardIds = new ArrayList<>();
        for (Map<String, Object> row : cheerRows) {
            Long cheerRowId = asLong(row.get("id"));
            String cheerCardId = asText(row.get("cheer_card_id"));
            if (cheerRowId == null || !StringUtils.hasText(cheerCardId)) {
                continue;
            }
            int deleted = jdbcTemplate.update(
                "DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?",
                cheerRowId,
                targetHolomemId
            );
            if (deleted != 1) {
                continue;
            }
            removedCheerCardIds.add(cheerCardId);
            Long archivedCardInstanceId = moveCheerCardInstanceToArchive(matchId, targetOwnerUserId, cheerCardId);
            if (archivedCardInstanceId != null) {
                archivedCardInstanceIds.add(archivedCardInstanceId);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("removeRequested", removeCount);
        summary.put("removeApplied", removedCheerCardIds.size());
        summary.put("removedCheerCardIds", removedCheerCardIds);
        summary.put("archivedCheerCardInstanceIds", archivedCardInstanceIds);
        return summary;
    }

    private Map<String, Object> executeMoveZoneEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("MOVE_ZONE 找不到目標 Holomen");
        }
        Map<String, Object> holomem = jdbcTemplate.query(
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
        if (holomem == null) {
            throw new IllegalStateException("MOVE_ZONE 結算失敗：找不到目標 Holomen");
        }

        Long targetOwnerUserId = asLong(holomem.get("owner_user_id"));
        String fromZone = normalize(holomem.get("zone"));
        String toZone = resolveMoveDestinationZone(effectNode);
        boolean restAfterMove = shouldRestAfterMove(effectNode);
        if (!"BACK".equals(toZone) && !"CENTER".equals(toZone) && !"COLLAB".equals(toZone)) {
            toZone = "BACK";
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

        if ("BACK".equals(toZone) && targetOwnerUserId != null) {
            Integer backCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'BACK'
                """,
                Integer.class,
                matchId,
                targetOwnerUserId
            );
            if (backCount != null && backCount >= 5) {
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 BACK 已滿");
            }
        }
        if ("CENTER".equals(toZone) && targetOwnerUserId != null) {
            Integer centerCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'CENTER'
                """,
                Integer.class,
                matchId,
                targetOwnerUserId
            );
            if (centerCount != null && centerCount > 0) {
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 CENTER 已有 Holomen");
            }
        }

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

        Boolean restedAfterMove = jdbcTemplate.query(
            "SELECT is_rested FROM match_holomems WHERE id = ? AND match_id = ?",
            rs -> rs.next() ? rs.getBoolean("is_rested") : null,
            targetHolomemId,
            matchId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("fromZone", fromZone);
        summary.put("toZone", toZone);
        summary.put("rested", restedAfterMove);
        summary.put("moved", true);
        return summary;
    }

    private Map<String, Object> executeBuffDebuffEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType
    ) {
        int modifier = resolveBuffDebuffModifier(effectNode, effectType);
        int currentTurn = resolveCurrentTurnNumber(matchId);
        int expiresTurn = currentTurn;
        List<Long> affectedUserIds = resolveAffectedUserIds(matchId, userId, targetType);
        int inserted = 0;
        if (modifier != 0 && !affectedUserIds.isEmpty()) {
            for (Long affectedUserId : affectedUserIds) {
                if (affectedUserId == null || affectedUserId <= 0) {
                    continue;
                }
                inserted += jdbcTemplate.update(
                    """
                    INSERT INTO match_turn_effects (
                        match_id,
                        source_user_id,
                        affected_user_id,
                        effect_type,
                        stat_type,
                        modifier_value,
                        expires_turn,
                        payload
                    ) VALUES (?, ?, ?, ?, 'DAMAGE_MODIFIER', ?, ?, CAST(? AS jsonb))
                    """,
                    matchId,
                    userId,
                    affectedUserId,
                    effectType,
                    modifier,
                    expiresTurn,
                    toJsonString(Map.of("rawText", extractText(effectNode, "rawText", "rawEffect", "rawHeader")))
                );
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted > 0);
        summary.put("statType", "DAMAGE_MODIFIER");
        summary.put("modifierValue", modifier);
        summary.put("currentTurn", currentTurn);
        summary.put("expiresTurn", expiresTurn);
        summary.put("affectedUserIds", affectedUserIds);
        summary.put("appliedCount", inserted);
        if (modifier == 0) {
            summary.put("reason", "找不到可用的傷害修正值");
        }
        return summary;
    }

    private Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        summary.put("rawText", extractText(effectNode, "rawText", "rawEffect"));
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

    public int clearExpiredTurnEffects(Long matchId, int currentTurn) {
        return jdbcTemplate.update(
            """
            DELETE FROM match_turn_effects
            WHERE match_id = ?
              AND expires_turn <= ?
            """,
            matchId,
            currentTurn
        );
    }

    private List<String> resolveEffectTypes(String effectType, JsonNode effectNode) {
        Set<String> effectTypes = new LinkedHashSet<>();
        if (StringUtils.hasText(effectType)) {
            effectTypes.add(normalizeEffectType(effectType));
        }
        if (effectNode != null && effectNode.hasNonNull("type")) {
            effectTypes.add(normalizeEffectType(effectNode.path("type").asText()));
        }
        if (effectNode != null) {
            JsonNode effectsNode = effectNode.get("effects");
            if (effectsNode != null && effectsNode.isArray()) {
                for (JsonNode node : effectsNode) {
                    if (node.isTextual() && StringUtils.hasText(node.asText())) {
                        effectTypes.add(normalizeEffectType(node.asText()));
                    }
                }
            }
        }
        return new ArrayList<>(effectTypes);
    }

    private int resolveSearchCount(JsonNode effectNode) {
        int fromFields = extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        Matcher range = SEARCH_RANGE_PATTERN.matcher(text);
        if (range.find()) {
            try {
                return Integer.parseInt(range.group(2));
            } catch (NumberFormatException ignored) {
                // 解析失敗時回退到下一規則
            }
        }
        int count = extractByPattern(text, SEARCH_COUNT_PATTERN);
        if (count > 0) {
            return count;
        }
        return text.contains("手札に加える") ? 1 : 0;
    }

    private SearchCriteria resolveSearchCriteria(JsonNode effectNode) {
        JsonNode criteriaNode = effectNode == null ? null : effectNode.get("searchCriteria");
        String cardType = normalizeCardType(readText(criteriaNode, "cardType"));
        String levelType = normalizeLevelType(readText(criteriaNode, "level", "levelType"));
        String tag = readText(criteriaNode, "tag");
        String nameContains = readText(criteriaNode, "nameContains");

        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!StringUtils.hasText(cardType)) {
            if (rawText.contains("ホロメン")) {
                cardType = "MEMBER";
            } else if (rawText.contains("エール")) {
                cardType = "CHEER";
            } else if (rawText.contains("サポート")) {
                cardType = "SUPPORT";
            }
        }
        if (!StringUtils.hasText(levelType)) {
            if (rawText.contains("Debut")) {
                levelType = "DEBUT";
            } else if (rawText.contains("1st")) {
                levelType = "FIRST";
            } else if (rawText.contains("2nd")) {
                levelType = "SECOND";
            } else if (rawText.contains("Buzz")) {
                levelType = "BUZZ";
            } else if (rawText.contains("Spot")) {
                levelType = "SPOT";
            }
        }
        if (!StringUtils.hasText(tag)) {
            Matcher matcher = TAG_PATTERN.matcher(rawText);
            if (matcher.find()) {
                tag = matcher.group();
            }
        }
        return new SearchCriteria(cardType, levelType, tag, nameContains);
    }

    private List<Map<String, Object>> loadSearchCandidates(
        Long matchId,
        Long userId,
        String cardType,
        String levelType,
        String tag,
        String nameContains
    ) {
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, c.card_type, m.level_type, c.name, c.tags_json::text AS tags_json
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'DECK'
              AND (? = '' OR c.card_type = ?)
              AND (? = '' OR m.level_type = ?)
              AND (? = '' OR c.name ILIKE '%' || ? || '%')
              AND (
                    ? = ''
                    OR EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(COALESCE(c.tags_json, '[]'::jsonb)) AS t(tag)
                        WHERE t.tag = ?
                    )
                  )
            ORDER BY mc.order_index NULLS LAST, mc.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("card_type", rs.getString("card_type"));
                row.put("level_type", rs.getString("level_type"));
                row.put("name", rs.getString("name"));
                row.put("tags_json", rs.getString("tags_json"));
                return row;
            },
            matchId,
            userId,
            nullToEmpty(cardType),
            nullToEmpty(cardType),
            nullToEmpty(levelType),
            nullToEmpty(levelType),
            nullToEmpty(nameContains),
            nullToEmpty(nameContains),
            nullToEmpty(tag),
            nullToEmpty(tag)
        );
    }

    private List<Map<String, Object>> selectSearchCards(
        List<Map<String, Object>> candidates,
        List<Long> selectedCardInstanceIds,
        int searchCount
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Object>> candidateById = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            Long id = asLong(candidate.get("id"));
            if (id != null) {
                candidateById.put(id, candidate);
            }
        }

        if (selectedCardInstanceIds == null || selectedCardInstanceIds.isEmpty()) {
            return new ArrayList<>(candidates.subList(0, Math.min(searchCount, candidates.size())));
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        for (Long requestedId : selectedCardInstanceIds) {
            if (requestedId == null || requestedId <= 0 || !visited.add(requestedId)) {
                continue;
            }
            Map<String, Object> candidate = candidateById.get(requestedId);
            if (candidate == null) {
                throw new IllegalArgumentException("SEARCH 選牌無效：包含不在候選中的 cardInstanceId=" + requestedId);
            }
            selected.add(candidate);
            if (selected.size() >= searchCount) {
                break;
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("SEARCH 選牌無效：未選到可用卡片");
        }
        return selected;
    }

    private String readText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private String normalizeCardType(String cardType) {
        String normalized = normalize(cardType);
        if ("MEMBER".equals(normalized) || "SUPPORT".equals(normalized) || "CHEER".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String normalizeLevelType(String levelType) {
        String normalized = normalize(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private Long resolveEffectTargetHolomemId(
        Long matchId,
        Long userId,
        String targetType,
        Long requestedTargetCardInstanceId,
        boolean defaultOpponent
    ) {
        String normalizedTargetType = normalize(targetType);
        boolean bothSides = normalizedTargetType.contains("BOTH") || normalizedTargetType.contains("ALL");
        boolean explicitSelf = normalizedTargetType.startsWith("SELF");
        boolean preferOpponent = isOpponentTargetType(normalizedTargetType) || (!explicitSelf && defaultOpponent);
        if (bothSides && requestedTargetCardInstanceId != null && requestedTargetCardInstanceId > 0) {
            Long selfRequested = resolveTargetHolomemId(matchId, userId, requestedTargetCardInstanceId);
            if (selfRequested != null) {
                return selfRequested;
            }
            Long opponentUserId = resolveOpponentUserId(matchId, userId);
            return resolveOpponentTargetHolomemId(
                matchId,
                opponentUserId,
                requestedTargetCardInstanceId
            );
        }
        if (preferOpponent) {
            Long opponentUserId = resolveOpponentUserId(matchId, userId);
            Long opponentTarget = resolveOpponentTargetHolomemId(
                matchId,
                opponentUserId,
                requestedTargetCardInstanceId
            );
            if (opponentTarget != null || !bothSides) {
                return opponentTarget;
            }
            return resolveTargetHolomemId(matchId, userId, requestedTargetCardInstanceId);
        }
        Long selfTarget = resolveTargetHolomemId(matchId, userId, requestedTargetCardInstanceId);
        if (selfTarget != null || !bothSides) {
            return selfTarget;
        }
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        return resolveOpponentTargetHolomemId(matchId, opponentUserId, requestedTargetCardInstanceId);
    }

    private boolean isOpponentTargetType(String targetType) {
        return targetType.contains("ENEMY") || targetType.contains("OPPONENT");
    }

    private Long resolveHolomemOwner(Long matchId, Long holomemId) {
        if (holomemId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT owner_user_id
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
            """,
            rs -> rs.next() ? rs.getLong("owner_user_id") : null,
            holomemId,
            matchId
        );
    }

    private int resolveDrawCount(JsonNode effectNode) {
        int fromFields = extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        int exact = extractByPattern(text, DRAW_COUNT_PATTERN);
        if (exact > 0) {
            return exact;
        }
        int fallback = extractByPattern(text, DRAW_COUNT_FALLBACK_PATTERN);
        if (fallback > 0) {
            return fallback;
        }
        return text.contains("引く") ? 1 : 0;
    }

    private int resolveBuffDebuffModifier(JsonNode effectNode, String effectType) {
        int fromFields = extractInt(effectNode, 0, "modifier", "damageModifier", "amount", "value");
        if (fromFields != 0) {
            return normalizeModifierSign(fromFields, effectType);
        }
        String text = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        Matcher matcher = ARTS_MODIFIER_PATTERN.matcher(text);
        if (matcher.find()) {
            String token = matcher.group(1).replace("＋", "+").replace("−", "-").replaceAll("\\s+", "");
            try {
                int parsed = Integer.parseInt(token);
                return normalizeModifierSign(parsed, effectType);
            } catch (NumberFormatException ignored) {
                // 解析失敗改走 fallback
            }
        }
        return 0;
    }

    private int normalizeModifierSign(int modifier, String effectType) {
        if (modifier == 0) {
            return 0;
        }
        return "DEBUFF".equals(effectType)
            ? -Math.abs(modifier)
            : modifier;
    }

    private List<Long> resolveAffectedUserIds(Long matchId, Long userId, String targetType) {
        String normalizedTargetType = normalize(targetType);
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        List<Long> affected = new ArrayList<>();
        boolean bothSides = normalizedTargetType.contains("BOTH") || normalizedTargetType.contains("ALL");
        if (bothSides) {
            affected.add(userId);
            if (opponentUserId != null && !opponentUserId.equals(userId)) {
                affected.add(opponentUserId);
            }
            return affected;
        }
        if (isOpponentTargetType(normalizedTargetType)) {
            if (opponentUserId != null) {
                affected.add(opponentUserId);
            }
            return affected;
        }
        affected.add(userId);
        return affected;
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

    private int resolveActiveDamageModifier(Long matchId, Long affectedUserId, int currentTurn) {
        if (affectedUserId == null || affectedUserId <= 0) {
            return 0;
        }
        Integer modifier = jdbcTemplate.query(
            """
            SELECT COALESCE(SUM(modifier_value), 0) AS total
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'DAMAGE_MODIFIER'
              AND expires_turn >= ?
            """,
            rs -> rs.next() ? rs.getInt("total") : 0,
            matchId,
            affectedUserId,
            currentTurn
        );
        return modifier == null ? 0 : modifier;
    }

    private int resolveCheerCount(JsonNode effectNode, int defaultValue) {
        int fromFields = extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        int byText = extractByPattern(normalizeDigits(extractText(effectNode, "rawText", "rawEffect")), CHEER_COUNT_PATTERN);
        if (byText > 0) {
            return byText;
        }
        return defaultValue;
    }

    private int resolveHealValue(JsonNode effectNode) {
        int fromFields = extractInt(effectNode, 0, "value", "amount", "heal");
        if (fromFields > 0) {
            return fromFields;
        }
        return extractByPattern(normalizeDigits(extractText(effectNode, "rawText", "rawEffect")), HEAL_PATTERN);
    }

    private String resolveMoveDestinationZone(JsonNode effectNode) {
        String explicit = normalizeEffectType(extractText(effectNode, "toZone", "targetZone"));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String text = extractText(effectNode, "rawText", "rawEffect");
        if (text.contains("コラボ")) {
            return "COLLAB";
        }
        if (text.contains("センター")) {
            return "CENTER";
        }
        if (text.contains("バック")) {
            return "BACK";
        }
        return "BACK";
    }

    private boolean shouldRestAfterMove(JsonNode effectNode) {
        String text = extractText(effectNode, "rawText", "rawEffect");
        return text.contains("お休み");
    }

    private List<Long> archiveAttachedCheerCards(Long matchId, Long matchHolomemId, Long ownerUserId) {
        if (matchHolomemId == null || ownerUserId == null) {
            return List.of();
        }
        List<String> cheerCardIds = jdbcTemplate.query(
            """
            SELECT cheer_card_id
            FROM match_holomem_cheers
            WHERE match_holomem_id = ?
            ORDER BY id
            """,
            (rs, rowNum) -> rs.getString("cheer_card_id"),
            matchHolomemId
        );
        if (cheerCardIds.isEmpty()) {
            return List.of();
        }
        List<Long> archived = new ArrayList<>();
        for (String cheerCardId : cheerCardIds) {
            Long archivedCardInstanceId = moveCheerCardInstanceToArchive(matchId, ownerUserId, cheerCardId);
            if (archivedCardInstanceId != null) {
                archived.add(archivedCardInstanceId);
            }
        }
        return archived;
    }

    private Long moveCheerCardInstanceToArchive(Long matchId, Long ownerUserId, String cheerCardId) {
        if (!StringUtils.hasText(cheerCardId)) {
            return null;
        }
        Long cheerCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'STAGE'
              AND card_id = ?
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cheerCardId
        );
        if (cheerCardInstanceId == null) {
            return null;
        }
        int archiveOrder = nextZoneOrder(matchId, ownerUserId, "ARCHIVE");
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = ?,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'STAGE'
            """,
            archiveOrder,
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
        return updated == 1 ? cheerCardInstanceId : null;
    }

    private Long resolveTargetHolomemId(Long matchId, Long userId, Long targetHolomemCardInstanceId) {
        if (targetHolomemCardInstanceId != null && targetHolomemCardInstanceId > 0) {
            return jdbcTemplate.query(
                """
                SELECT id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND match_card_id = ?
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                userId,
                targetHolomemCardInstanceId
            );
        }
        Long centerId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (centerId != null) {
            return centerId;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            ORDER BY CASE zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
    }

    private Long resolveHolomemCardInstanceId(Long matchHolomemId) {
        if (matchHolomemId == null) {
            return null;
        }
        return jdbcTemplate.query(
            "SELECT match_card_id FROM match_holomems WHERE id = ?",
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchHolomemId
        );
    }

    private Map<String, Object> findAttachableCheerCard(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, mc.zone
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone IN ('CHEER_DECK','ARCHIVE','HAND')
            ORDER BY CASE mc.zone WHEN 'CHEER_DECK' THEN 1 WHEN 'ARCHIVE' THEN 2 WHEN 'HAND' THEN 3 ELSE 9 END,
                     mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                return row;
            },
            matchId,
            userId
        );
    }

    private Long resolveOpponentUserId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT player_a_id, player_b_id
            FROM matches
            WHERE id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Long a = asLong(rs.getObject("player_a_id"));
                Long b = asLong(rs.getObject("player_b_id"));
                if (a != null && !a.equals(userId)) {
                    return a;
                }
                if (b != null && !b.equals(userId)) {
                    return b;
                }
                return null;
            },
            matchId
        );
    }

    private Long resolveOpponentTargetHolomemId(
        Long matchId,
        Long opponentUserId,
        Long requestedTargetCardInstanceId
    ) {
        if (opponentUserId == null) {
            return null;
        }
        if (requestedTargetCardInstanceId != null && requestedTargetCardInstanceId > 0) {
            return jdbcTemplate.query(
                """
                SELECT id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND match_card_id = ?
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                opponentUserId,
                requestedTargetCardInstanceId
            );
        }
        Long center = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            opponentUserId
        );
        if (center != null) {
            return center;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            ORDER BY CASE zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            opponentUserId
        );
    }

    private Long loseLifeOnce(Long matchId, Long ownerUserId) {
        Long lifeCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'LIFE'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId
        );
        if (lifeCardInstanceId == null) {
            return null;
        }
        int archiveOrder = nextZoneOrder(matchId, ownerUserId, "ARCHIVE");
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
              AND zone = 'LIFE'
            """,
            archiveOrder,
            lifeCardInstanceId,
            matchId,
            ownerUserId
        );
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = GREATEST(COALESCE(current_life, 0) - 1, 0),
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            ownerUserId
        );
        return lifeCardInstanceId;
    }

    private boolean isDownWithoutLifeLoss(JsonNode effectNode) {
        if (effectNode != null && effectNode.has("downDoesNotReduceLife")) {
            return effectNode.path("downDoesNotReduceLife").asBoolean(false);
        }
        String merged = extractText(effectNode, "rawText", "rawEffect");
        return StringUtils.hasText(merged) && merged.contains("ダウンしても相手のライフは減らない");
    }

    private int resolveDamageValue(JsonNode effectNode) {
        int fromFields = extractInt(effectNode, 0, "value", "amount", "damage");
        if (fromFields > 0) {
            return fromFields;
        }
        String merged = normalizeDigits(extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        int special = extractByPattern(merged, SPECIAL_DAMAGE_PATTERN);
        if (special > 0) {
            return special;
        }
        int normal = extractByPattern(merged, DAMAGE_PATTERN);
        if (normal > 0) {
            return normal;
        }
        return 0;
    }

    private int extractByPattern(String value, Pattern pattern) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String extractText(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (String fieldName : fieldNames) {
            JsonNode textNode = node.get(fieldName);
            if (textNode != null && textNode.isTextual() && StringUtils.hasText(textNode.asText())) {
                if (!merged.isEmpty()) {
                    merged.append('\n');
                }
                merged.append(textNode.asText());
            }
        }
        return merged.toString();
    }

    private JsonNode parseEffectJson(String effectJson) {
        if (!StringUtils.hasText(effectJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(effectJson);
        } catch (Exception ex) {
            return null;
        }
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private int extractInt(JsonNode node, int defaultValue, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return defaultValue;
        }
        for (String fieldName : fieldNames) {
            JsonNode valueNode = node.get(fieldName);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            if (valueNode.isInt() || valueNode.isLong()) {
                return valueNode.asInt();
            }
            if (valueNode.isTextual()) {
                try {
                    return Integer.parseInt(normalizeDigits(valueNode.asText()).trim());
                } catch (NumberFormatException ignored) {
                    // 略過非法字串，改讀其他欄位
                }
            }
        }
        return defaultValue;
    }

    private String normalizeDigits(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c >= '０' && c <= '９') {
                builder.append((char) ('0' + (c - '０')));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private String normalizeEffectType(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private record SearchCriteria(
        String cardType,
        String levelType,
        String tag,
        String nameContains
    ) {}
}
