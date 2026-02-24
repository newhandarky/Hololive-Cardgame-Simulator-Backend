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
    private static final Pattern NAME_TOKEN_PATTERN = Pattern.compile("〈([^〉]+)〉");
    private static final Pattern ARTS_MODIFIER_PATTERN = Pattern.compile("アーツ\\s*([+＋\\-−]\\s*\\d+)");
    private static final Pattern DICE_AT_LEAST_PATTERN = Pattern.compile("(\\d+)\\s*以上の時");
    private static final Pattern DICE_AT_MOST_PATTERN = Pattern.compile("(\\d+)\\s*以下の時");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DiceService diceService;

    public MatchEffectService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, DiceService diceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.diceService = diceService;
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
                case "RETURN_TO_HAND" -> executed.add(
                    executeReturnToHandEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
                );
                case "RETURN_TO_DECK_TOP" -> executed.add(
                    executeReturnToDeckTopEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
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
                case "REATTACH" -> executed.add(
                    executeReattachEffect(
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
                case "SUMMON_TO_STAGE" -> executed.add(
                    executeSummonToStageEffect(matchId, userId, type, effectNode)
                );
                case "REVEAL_TO_ARCHIVE" -> executed.add(
                    executeRevealToArchiveEffect(matchId, userId, type, effectNode)
                );
                case "BLOOM_FROM_ARCHIVE" -> executed.add(
                    executeBloomFromArchiveEffect(matchId, userId, type, effectNode)
                );
                case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                    executeReturnCheerToDeckBottomEffect(matchId, userId, type, effectNode)
                );
                case "SWAP_WITH_COLLAB" -> executed.add(
                    executeSwapWithCollabEffect(matchId, userId, type, effectNode, targetHolomemCardInstanceId)
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

    public SupportDecisionPlan buildSupportDecisionPlan(
        Long matchId,
        Long userId,
        String effectType,
        String effectJson
    ) {
        JsonNode effectNode = parseEffectJson(effectJson);
        List<String> effectTypes = resolveEffectTypes(effectType, effectNode);
        for (String type : effectTypes) {
            SelectionProbe probe = probeSelectionCandidates(matchId, userId, type, effectNode);
            if (probe == null || probe.candidates().isEmpty()) {
                continue;
            }
            int maxSelect = Math.min(probe.requestedCount(), probe.candidates().size());
            if (maxSelect <= 0 || probe.candidates().size() <= maxSelect) {
                continue;
            }
            return new SupportDecisionPlan(
                normalizeEffectType(type),
                1,
                maxSelect,
                probe.candidates()
            );
        }
        return null;
    }

    public Map<String, Object> applyArtDamage(
        Long matchId,
        Long userId,
        int baseDamage,
        Long targetHolomemCardInstanceId
    ) {
        if (baseDamage <= 0) {
            throw new IllegalArgumentException("藝能傷害必須大於 0");
        }
        JsonNode effectNode = objectMapper.valueToTree(Map.of("type", "DAMAGE", "value", baseDamage));
        return executeDamageEffect(
            matchId,
            userId,
            "ART_DAMAGE",
            effectNode,
            "ENEMY",
            targetHolomemCardInstanceId
        );
    }

    public Map<String, Object> applyBloomTriggeredEffects(
        Long matchId,
        Long userId,
        String bloomCardId,
        Long selfHolomemCardInstanceId
    ) {
        String bloomText = loadBloomEffectText(bloomCardId);
        if (!StringUtils.hasText(bloomText)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("hasBloomEffect", false);
            summary.put("requestedEffects", List.of());
            summary.put("executedEffects", List.of());
            summary.put("unsupportedEffects", List.of());
            summary.put("rawText", null);
            return summary;
        }

        List<String> effectTypes = inferBloomEffectTypes(bloomText);
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        Integer diceRoll = resolveBloomDiceRoll(bloomText);
        Map<String, Object> bloomEffectPayload = new LinkedHashMap<>();
        bloomEffectPayload.put("type", "UNIMPLEMENTED");
        bloomEffectPayload.put("effects", effectTypes);
        bloomEffectPayload.put("rawText", bloomText);
        if (diceRoll != null) {
            bloomEffectPayload.put("diceRoll", diceRoll);
        }
        JsonNode bloomEffectNode = objectMapper.valueToTree(bloomEffectPayload);

        for (String effectType : effectTypes) {
            String targetType = inferBloomTargetType(effectType);
            switch (effectType) {
                case "DRAW" -> executed.add(executeDrawEffect(matchId, userId, effectType, bloomEffectNode));
                case "SEARCH" -> executed.add(
                    executeSearchEffect(matchId, userId, effectType, bloomEffectNode, null)
                );
                case "RETURN_TO_HAND" -> executed.add(
                    executeReturnToHandEffect(matchId, userId, effectType, bloomEffectNode, null)
                );
                case "RETURN_TO_DECK_TOP" -> executed.add(
                    executeReturnToDeckTopEffect(matchId, userId, effectType, bloomEffectNode, null)
                );
                case "ADD_CHEER" -> executed.add(
                    executeAddCheerEffect(
                        matchId,
                        userId,
                        effectType,
                        bloomEffectNode,
                        targetType,
                        selfHolomemCardInstanceId
                    )
                );
                case "DAMAGE" -> executed.add(
                    executeDamageEffect(
                        matchId,
                        userId,
                        effectType,
                        bloomEffectNode,
                        targetType,
                        null
                    )
                );
                case "REATTACH" -> executed.add(
                    executeReattachEffect(
                        matchId,
                        userId,
                        effectType,
                        bloomEffectNode,
                        targetType,
                        selfHolomemCardInstanceId
                    )
                );
                case "SUMMON_TO_STAGE" -> executed.add(
                    executeSummonToStageEffect(matchId, userId, effectType, bloomEffectNode)
                );
                case "REVEAL_TO_ARCHIVE" -> executed.add(
                    executeRevealToArchiveEffect(matchId, userId, effectType, bloomEffectNode)
                );
                case "BLOOM_FROM_ARCHIVE" -> executed.add(
                    executeBloomFromArchiveEffect(matchId, userId, effectType, bloomEffectNode)
                );
                case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                    executeReturnCheerToDeckBottomEffect(matchId, userId, effectType, bloomEffectNode)
                );
                case "MOVE_ZONE" -> executed.add(
                    executeMoveZoneEffect(
                        matchId,
                        userId,
                        effectType,
                        bloomEffectNode,
                        targetType,
                        null
                    )
                );
                case "SWAP_WITH_COLLAB" -> executed.add(
                    executeSwapWithCollabEffect(matchId, userId, effectType, bloomEffectNode, selfHolomemCardInstanceId)
                );
                case "HEAL" -> executed.add(
                    executeHealEffect(
                        matchId,
                        userId,
                        effectType,
                        bloomEffectNode,
                        targetType,
                        selfHolomemCardInstanceId
                    )
                );
                case "BUFF", "DEBUFF" -> executed.add(
                    executeBuffDebuffEffect(
                        matchId,
                        userId,
                        effectType,
                        bloomEffectNode,
                        targetType
                    )
                );
                case "UNIMPLEMENTED" -> executed.add(
                    executeNoOpEffect(effectType, bloomEffectNode, "尚未支援的 BLOOM 效果")
                );
                default -> unsupported.add(effectType);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hasBloomEffect", true);
        summary.put("requestedEffects", effectTypes);
        summary.put("executedEffects", executed);
        summary.put("unsupportedEffects", unsupported);
        summary.put("rawText", bloomText);
        if (diceRoll != null) {
            summary.put("diceRoll", diceRoll);
        }
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

    private Map<String, Object> executeReturnToHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        int requestedCount = resolveActionCount(effectNode, "手札に戻", 1);
        int returnCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        boolean excludeLimitedSupport = rawText.contains("LIMITED以外");

        List<Map<String, Object>> candidates = loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria.cardType(),
            criteria.levelType(),
            criteria.tag(),
            criteria.nameContains(),
            excludeLimitedSupport
        );
        List<Map<String, Object>> selected = selectSearchCards(candidates, selectedCardInstanceIds, returnCount);

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
                  AND zone = 'ARCHIVE'
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
        criteriaSummary.put("excludeLimitedSupport", excludeLimitedSupport);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("returnRequested", returnCount);
        summary.put("candidateCount", candidates.size());
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        summary.put("criteria", criteriaSummary);
        return summary;
    }

    private Map<String, Object> executeReturnToDeckTopEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        int requestedCount = resolveActionCount(effectNode, "デッキの上に戻", 1);
        int returnCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);

        List<Map<String, Object>> candidates = loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria.cardType(),
            criteria.levelType(),
            criteria.tag(),
            criteria.nameContains(),
            false
        );
        List<Map<String, Object>> selected = selectSearchCards(candidates, selectedCardInstanceIds, returnCount);

        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        Integer topDeckOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1) - 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            matchId,
            userId
        );
        int nextTopOrder = topDeckOrder == null ? 0 : topDeckOrder;
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            int updated = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'DECK',
                    order_index = ?,
                    is_face_down = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'ARCHIVE'
                """,
                nextTopOrder--,
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
        summary.put("returnRequested", returnCount);
        summary.put("candidateCount", candidates.size());
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        summary.put("criteria", criteriaSummary);
        return summary;
    }

    private Map<String, Object> executeReattachEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        if (!rawText.contains("エール")) {
            return executeNoOpEffect(effectType, effectNode, "目前僅支援 Cheer 的付け/付け替え");
        }

        boolean opponentContext = rawText.contains("相手の");
        Long sourceOwnerUserId = opponentContext ? resolveOpponentUserId(matchId, userId) : userId;
        if (sourceOwnerUserId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可操作的玩家");
        }
        String effectiveTargetType = opponentContext ? "ENEMY" : targetType;

        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            effectiveTargetType,
            targetHolomemCardInstanceId,
            false
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("REATTACH 找不到目標 Holomen");
        }
        Long targetOwnerUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("REATTACH 結算失敗：找不到目標擁有者");
        }
        if (!targetOwnerUserId.equals(sourceOwnerUserId)) {
            targetHolomemId = resolveTargetHolomemId(matchId, sourceOwnerUserId, null);
            if (targetHolomemId == null) {
                throw new IllegalStateException("REATTACH 結算失敗：找不到可附加的目標 Holomen");
            }
            targetOwnerUserId = sourceOwnerUserId;
        }

        int requestedCount = resolveActionCount(effectNode, "付け", 1);
        int moveCount = Math.max(requestedCount, 1);

        List<String> movedCheerCardIds = new ArrayList<>();
        List<Long> movedCheerRowIds = new ArrayList<>();
        String sourceMode;
        if (rawText.contains("アーカイブ")) {
            sourceMode = "ARCHIVE";
            for (int i = 0; i < moveCount; i++) {
                Map<String, Object> archivedCheer = findCheerCardFromZone(matchId, sourceOwnerUserId, "ARCHIVE");
                if (archivedCheer == null) {
                    break;
                }
                Long cardInstanceId = asLong(archivedCheer.get("id"));
                String cheerCardId = asText(archivedCheer.get("card_id"));
                if (cardInstanceId == null || !StringUtils.hasText(cheerCardId)) {
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
                      AND zone = 'ARCHIVE'
                    """,
                    cardInstanceId,
                    matchId,
                    sourceOwnerUserId
                );
                if (moved != 1) {
                    continue;
                }
                Long cheerRowId = jdbcTemplate.query(
                    """
                    INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cheerCardId
                );
                movedCheerCardIds.add(cheerCardId);
                if (cheerRowId != null) {
                    movedCheerRowIds.add(cheerRowId);
                }
            }
        } else {
            sourceMode = "STAGE";
            List<Map<String, Object>> attachedRows = jdbcTemplate.queryForList(
                """
                SELECT c.id AS cheer_row_id,
                       c.cheer_card_id,
                       c.match_holomem_id
                FROM match_holomem_cheers c
                JOIN match_holomems h ON h.id = c.match_holomem_id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                ORDER BY CASE WHEN c.match_holomem_id = ? THEN 1 ELSE 0 END, c.id
                LIMIT ?
                """,
                matchId,
                sourceOwnerUserId,
                targetHolomemId,
                moveCount * 2
            );
            for (Map<String, Object> row : attachedRows) {
                if (movedCheerCardIds.size() >= moveCount) {
                    break;
                }
                Long cheerRowId = asLong(row.get("cheer_row_id"));
                Long fromHolomemId = asLong(row.get("match_holomem_id"));
                String cheerCardId = asText(row.get("cheer_card_id"));
                if (cheerRowId == null || !StringUtils.hasText(cheerCardId)) {
                    continue;
                }
                if (targetHolomemId.equals(fromHolomemId)) {
                    continue;
                }
                int deleted = jdbcTemplate.update(
                    "DELETE FROM match_holomem_cheers WHERE id = ?",
                    cheerRowId
                );
                if (deleted != 1) {
                    continue;
                }
                Long newCheerRowId = jdbcTemplate.query(
                    """
                    INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cheerCardId
                );
                movedCheerCardIds.add(cheerCardId);
                if (newCheerRowId != null) {
                    movedCheerRowIds.add(newCheerRowId);
                }
            }
            if (movedCheerCardIds.isEmpty() && rawText.contains("エールデッキ")) {
                sourceMode = "CHEER_DECK";
                for (int i = 0; i < moveCount; i++) {
                    Map<String, Object> cheerDeckTop = findCheerCardFromZone(matchId, sourceOwnerUserId, "CHEER_DECK");
                    if (cheerDeckTop == null) {
                        break;
                    }
                    Long cardInstanceId = asLong(cheerDeckTop.get("id"));
                    String cheerCardId = asText(cheerDeckTop.get("card_id"));
                    if (cardInstanceId == null || !StringUtils.hasText(cheerCardId)) {
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
                          AND zone = 'CHEER_DECK'
                        """,
                        cardInstanceId,
                        matchId,
                        sourceOwnerUserId
                    );
                    if (moved != 1) {
                        continue;
                    }
                    Long newCheerRowId = jdbcTemplate.query(
                        """
                        INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                        VALUES (?, ?, FALSE)
                        RETURNING id
                        """,
                        rs -> rs.next() ? rs.getLong("id") : null,
                        targetHolomemId,
                        cheerCardId
                    );
                    movedCheerCardIds.add(cheerCardId);
                    if (newCheerRowId != null) {
                        movedCheerRowIds.add(newCheerRowId);
                    }
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("moveRequested", moveCount);
        summary.put("moveApplied", movedCheerCardIds.size());
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("movedCheerCardIds", movedCheerCardIds);
        summary.put("movedCheerRowIds", movedCheerRowIds);
        summary.put("sourceMode", sourceMode);
        return summary;
    }

    private Map<String, Object> executeSummonToStageEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = resolveActionCount(effectNode, "ステージに出", 1);
        int summonCount = Math.max(requestedCount, 1);
        SearchCriteria resolved = resolveSearchCriteria(effectNode);
        SearchCriteria criteria = new SearchCriteria(
            "MEMBER",
            resolved.levelType(),
            resolved.tag(),
            resolved.nameContains()
        );
        List<Map<String, Object>> candidates = loadCandidatesFromZone(
            matchId,
            userId,
            "DECK",
            criteria.cardType(),
            criteria.levelType(),
            criteria.tag(),
            criteria.nameContains(),
            false
        );
        List<Map<String, Object>> selected = candidates.subList(0, Math.min(summonCount, candidates.size()));
        String preferredZone = resolveMoveDestinationZone(effectNode);
        int currentTurn = resolveCurrentTurnNumber(matchId);

        List<Long> summonedCardInstanceIds = new ArrayList<>();
        List<Long> summonedHolomemIds = new ArrayList<>();
        List<String> summonedCardIds = new ArrayList<>();
        List<String> summonedZones = new ArrayList<>();
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            String levelType = asText(row.get("level_type"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            String targetZone = resolveAvailableStageZone(matchId, userId, preferredZone);
            if (!StringUtils.hasText(targetZone)) {
                break;
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
                  AND zone = 'DECK'
                """,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                continue;
            }

            Long holomemId = jdbcTemplate.query(
                """
                INSERT INTO match_holomems (
                    match_id,
                    owner_user_id,
                    match_card_id,
                    card_id,
                    zone,
                    is_rested,
                    is_face_down,
                    damage_taken,
                    current_level,
                    entered_turn_number
                ) VALUES (?, ?, ?, ?, ?, FALSE, FALSE, 0, ?, ?)
                RETURNING id
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                userId,
                cardInstanceId,
                cardId,
                targetZone,
                normalizeHolomemLevel(levelType),
                currentTurn
            );
            if (holomemId == null) {
                continue;
            }
            recordHolomemStackCard(holomemId, cardInstanceId);

            summonedCardInstanceIds.add(cardInstanceId);
            summonedHolomemIds.add(holomemId);
            summonedCardIds.add(cardId);
            summonedZones.add(targetZone);
        }

        Map<String, Object> criteriaSummary = new LinkedHashMap<>();
        criteriaSummary.put("cardType", criteria.cardType());
        criteriaSummary.put("levelType", criteria.levelType());
        criteriaSummary.put("tag", criteria.tag());
        criteriaSummary.put("nameContains", criteria.nameContains());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("summonRequested", summonCount);
        summary.put("candidateCount", candidates.size());
        summary.put("summonApplied", summonedCardInstanceIds.size());
        summary.put("summonedCardInstanceIds", summonedCardInstanceIds);
        summary.put("summonedHolomemIds", summonedHolomemIds);
        summary.put("summonedCardIds", summonedCardIds);
        summary.put("summonedZones", summonedZones);
        summary.put("criteria", criteriaSummary);
        return summary;
    }

    private Map<String, Object> executeRevealToArchiveEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = resolveActionCount(effectNode, "アーカイブ", 1);
        int archiveCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        List<Map<String, Object>> candidates = loadCandidatesFromZone(
            matchId,
            userId,
            "DECK",
            criteria.cardType(),
            criteria.levelType(),
            criteria.tag(),
            criteria.nameContains(),
            false
        );
        List<Map<String, Object>> selected = candidates.subList(0, Math.min(archiveCount, candidates.size()));

        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> archivedCardIds = new ArrayList<>();
        int nextArchiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
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
                  AND zone = 'DECK'
                """,
                nextArchiveOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            archivedCardInstanceIds.add(cardInstanceId);
            archivedCardIds.add(cardId);
        }

        Map<String, Object> criteriaSummary = new LinkedHashMap<>();
        criteriaSummary.put("cardType", criteria.cardType());
        criteriaSummary.put("levelType", criteria.levelType());
        criteriaSummary.put("tag", criteria.tag());
        criteriaSummary.put("nameContains", criteria.nameContains());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("archiveRequested", archiveCount);
        summary.put("candidateCount", candidates.size());
        summary.put("archiveApplied", archivedCardInstanceIds.size());
        summary.put("archivedCardInstanceIds", archivedCardInstanceIds);
        summary.put("archivedCardIds", archivedCardIds);
        summary.put("criteria", criteriaSummary);
        return summary;
    }

    private Map<String, Object> executeBloomFromArchiveEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int currentTurn = resolveCurrentTurnNumber(matchId);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        String requiredLevel = StringUtils.hasText(criteria.levelType()) ? criteria.levelType() : "DEBUT";

        List<Map<String, Object>> targetCandidates = jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   h.current_level,
                   h.damage_taken,
                   h.last_bloom_turn,
                   c.name
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER','COLLAB','BACK')
              AND (? = '' OR h.current_level = ?)
              AND (? = '' OR c.name ILIKE '%' || ? || '%')
              AND (
                    ? = ''
                    OR EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(COALESCE(c.tags_json, '[]'::jsonb)) AS t(tag)
                        WHERE t.tag = ?
                    )
                  )
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END,
                     h.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("holomem_id", rs.getLong("holomem_id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("current_level", rs.getString("current_level"));
                row.put("damage_taken", rs.getInt("damage_taken"));
                row.put("last_bloom_turn", rs.getObject("last_bloom_turn"));
                row.put("name", rs.getString("name"));
                return row;
            },
            matchId,
            userId,
            nullToEmpty(requiredLevel),
            nullToEmpty(requiredLevel),
            nullToEmpty(criteria.nameContains()),
            nullToEmpty(criteria.nameContains()),
            nullToEmpty(criteria.tag()),
            nullToEmpty(criteria.tag())
        );
        Map<String, Object> target = targetCandidates.stream()
            .filter(row -> {
                Object lastBloomTurn = row.get("last_bloom_turn");
                if (lastBloomTurn instanceof Number number) {
                    return number.intValue() != currentTurn;
                }
                return true;
            })
            .findFirst()
            .orElse(null);
        if (target == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有可從 Archive 進行 Bloom 的目標");
        }

        Long targetHolomemId = asLong(target.get("holomem_id"));
        String targetName = asText(target.get("name"));
        String targetLevel = asText(target.get("current_level"));
        int targetDamageTaken = asInt(target.get("damage_taken"));
        int targetRank = resolveBloomLevelRank(targetLevel);
        if (targetHolomemId == null || !StringUtils.hasText(targetName) || targetRank < 0) {
            return executeNoOpEffect(effectType, effectNode, "目標 Holomem 資料不足，無法執行 Archive Bloom");
        }

        Map<String, Object> archiveBloomCard = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   m.level_type,
                   m.hp,
                   m.bloom_level
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'ARCHIVE'
              AND c.name = ?
              AND m.bloom_level > ?
              AND m.hp >= ?
            ORDER BY m.bloom_level, mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("card_instance_id", rs.getLong("card_instance_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("level_type", rs.getString("level_type"));
                row.put("hp", rs.getInt("hp"));
                row.put("bloom_level", rs.getInt("bloom_level"));
                return row;
            },
            matchId,
            userId,
            targetName,
            targetRank,
            targetDamageTaken
        );
        if (archiveBloomCard == null) {
            return executeNoOpEffect(effectType, effectNode, "Archive 中找不到可用的 Bloom 卡");
        }

        Long bloomCardInstanceId = asLong(archiveBloomCard.get("card_instance_id"));
        String bloomCardId = asText(archiveBloomCard.get("card_id"));
        String bloomLevelType = asText(archiveBloomCard.get("level_type"));
        if (bloomCardInstanceId == null || !StringUtils.hasText(bloomCardId)) {
            return executeNoOpEffect(effectType, effectNode, "Archive Bloom 卡資料不完整");
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
              AND zone = 'ARCHIVE'
            """,
            bloomCardInstanceId,
            matchId,
            userId
        );
        if (moved != 1) {
            return executeNoOpEffect(effectType, effectNode, "Archive Bloom 移動卡片失敗");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET match_card_id = ?,
                card_id = ?,
                current_level = ?,
                last_bloom_turn = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            bloomCardInstanceId,
            bloomCardId,
            normalizeHolomemLevel(bloomLevelType),
            currentTurn,
            targetHolomemId,
            matchId,
            userId
        );
        recordHolomemStackCard(targetHolomemId, bloomCardInstanceId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("bloomCardInstanceId", bloomCardInstanceId);
        summary.put("bloomCardId", bloomCardId);
        summary.put("bloomLevelType", normalizeHolomemLevel(bloomLevelType));
        return summary;
    }

    private Map<String, Object> executeReturnCheerToDeckBottomEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        String colorFilter = resolveCheerColorFilter(rawText);
        int requestedCount = resolveActionCount(effectNode, "エールデッキの下に戻", 1);
        int returnCount = Math.max(requestedCount, 1);

        List<Map<String, Object>> candidates = jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, cc.color
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'ARCHIVE'
              AND (? = '' OR cc.color = ?)
            ORDER BY mc.order_index NULLS LAST, mc.id
            LIMIT ?
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("color", rs.getString("color"));
                return row;
            },
            matchId,
            userId,
            nullToEmpty(colorFilter),
            nullToEmpty(colorFilter),
            returnCount
        );

        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        int nextCheerDeckOrder = nextZoneOrder(matchId, userId, "CHEER_DECK");
        for (Map<String, Object> row : candidates) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'CHEER_DECK',
                    order_index = ?,
                    is_face_down = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'ARCHIVE'
                """,
                nextCheerDeckOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                continue;
            }
            movedCardInstanceIds.add(cardInstanceId);
            movedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("returnRequested", returnCount);
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("colorFilter", colorFilter);
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        return summary;
    }

    private Map<String, Object> executeSwapWithCollabEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long selfHolomemCardInstanceId
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        Long sourceHolomemId = resolveTargetHolomemId(matchId, userId, selfHolomemCardInstanceId);
        if (sourceHolomemId == null) {
            sourceHolomemId = jdbcTemplate.query(
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
                userId
            );
        }
        if (sourceHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可交換的來源 Holomem");
        }

        Map<String, Object> source = jdbcTemplate.query(
            """
            SELECT h.id, h.zone, COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0) AS remain_hp
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.id = ?
              AND h.match_id = ?
              AND h.owner_user_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("zone", rs.getString("zone"));
                row.put("remain_hp", rs.getInt("remain_hp"));
                return row;
            },
            sourceHolomemId,
            matchId,
            userId
        );
        if (source == null) {
            return executeNoOpEffect(effectType, effectNode, "來源 Holomem 不存在");
        }
        String sourceZone = normalize(source.get("zone"));
        if (rawText.contains("バックポジション限定") && !"BACK".equals(sourceZone)) {
            return executeNoOpEffect(effectType, effectNode, "來源 Holomem 不在 BACK，無法交換");
        }

        boolean requireLowHpCollab = rawText.contains("残りHP70以下");
        Map<String, Object> collabTarget = jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0) AS remain_hp
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'COLLAB'
              AND (? = FALSE OR (COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0)) <= 70)
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("remain_hp", rs.getInt("remain_hp"));
                return row;
            },
            matchId,
            userId,
            requireLowHpCollab
        );
        if (collabTarget == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有符合條件的 COLLAB 目標可交換");
        }
        Long collabHolomemId = asLong(collabTarget.get("id"));
        if (collabHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "COLLAB 目標資料不足");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'COLLAB',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            sourceHolomemId,
            matchId,
            userId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'BACK',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            collabHolomemId,
            matchId,
            userId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("swapped", true);
        summary.put("sourceHolomemId", sourceHolomemId);
        summary.put("targetHolomemId", collabHolomemId);
        summary.put("sourceHolomemCardInstanceId", resolveHolomemCardInstanceId(sourceHolomemId));
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(collabHolomemId));
        summary.put("requireLowHpCollab", requireLowHpCollab);
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
        List<Long> archivedHolomemCardInstanceIds = new ArrayList<>();
        if (downed) {
            Long targetCardInstanceId = asLong(holomemState.get("match_card_id"));
            String targetZone = normalize(holomemState.get("zone"));
            archivedCheerCardInstanceIds = archiveAttachedCheerCards(matchId, targetHolomemId, targetOwnerUserId);
            archivedHolomemCardInstanceIds = archiveHolomemStackCards(matchId, targetHolomemId, targetOwnerUserId);

            jdbcTemplate.update(
                "DELETE FROM match_holomems WHERE id = ? AND match_id = ?",
                targetHolomemId,
                matchId
            );
            if (archivedHolomemCardInstanceIds.isEmpty() && targetCardInstanceId != null) {
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
        summary.put("archivedHolomemCardInstanceIds", archivedHolomemCardInstanceIds);
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
        String rawText = extractText(effectNode, "rawText", "rawEffect");
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
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
                holomem = jdbcTemplate.query(
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
                if (holomem != null) {
                    targetOwnerUserId = asLong(holomem.get("owner_user_id"));
                    fromZone = normalize(holomem.get("zone"));
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
        if ("COLLAB".equals(toZone) && targetOwnerUserId != null) {
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
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 COLLAB 已有 Holomen");
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

    private SelectionProbe probeSelectionCandidates(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String normalizedType = normalizeEffectType(effectType);
        return switch (normalizedType) {
            case "SEARCH" -> probeSearchCandidates(matchId, userId, effectNode);
            case "RETURN_TO_HAND" -> probeReturnToHandCandidates(matchId, userId, effectNode);
            case "RETURN_TO_DECK_TOP" -> probeReturnToDeckTopCandidates(matchId, userId, effectNode);
            default -> null;
        };
    }

    private SelectionProbe probeSearchCandidates(Long matchId, Long userId, JsonNode effectNode) {
        int requestedCount = Math.max(resolveSearchCount(effectNode), 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        List<Map<String, Object>> rows = loadSearchCandidates(
            matchId,
            userId,
            criteria.cardType(),
            criteria.levelType(),
            criteria.tag(),
            criteria.nameContains()
        );
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, "DECK")
        );
    }

    private SelectionProbe probeReturnToHandCandidates(Long matchId, Long userId, JsonNode effectNode) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, "RETURN_TO_HAND")) {
            return null;
        }
        int requestedCount = Math.max(resolveActionCount(effectNode, "手札に戻", 1), 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        boolean excludeLimitedSupport = rawText.contains("LIMITED以外");
        List<Map<String, Object>> rows = loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria.cardType(),
            criteria.levelType(),
            criteria.tag(),
            criteria.nameContains(),
            excludeLimitedSupport
        );
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, "ARCHIVE")
        );
    }

    private SelectionProbe probeReturnToDeckTopCandidates(Long matchId, Long userId, JsonNode effectNode) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, "RETURN_TO_DECK_TOP")) {
            return null;
        }
        int requestedCount = Math.max(resolveActionCount(effectNode, "デッキの上に戻", 1), 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        List<Map<String, Object>> rows = loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria.cardType(),
            criteria.levelType(),
            criteria.tag(),
            criteria.nameContains(),
            false
        );
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, "ARCHIVE")
        );
    }

    private List<DecisionCandidate> mapDecisionCandidates(List<Map<String, Object>> rows, String zone) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DecisionCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            candidates.add(
                new DecisionCandidate(
                    cardInstanceId,
                    cardId,
                    asText(row.get("name")),
                    normalize(asText(row.get("card_type"))),
                    normalizeLevelType(asText(row.get("level_type"))),
                    normalize(zone)
                )
            );
        }
        return candidates;
    }

    private String loadBloomEffectText(String bloomCardId) {
        if (!StringUtils.hasText(bloomCardId)) {
            return null;
        }
        String passiveText = jdbcTemplate.query(
            """
            SELECT passive_effect_json::text AS passive_text
            FROM member_cards
            WHERE card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("passive_text") : null,
            bloomCardId
        );
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("ブルームエフェクト")) {
            return null;
        }
        return normalizeBloomText(passiveText);
    }

    private String normalizeBloomText(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return "";
        }
        String normalized = passiveText
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("{", " ")
            .replace("}", " ")
            .replace("\"", " ")
            .replace(":", " ");
        int idx = normalized.indexOf("ブルームエフェクト");
        if (idx < 0) {
            return normalized.trim();
        }
        String trimmed = normalized.substring(idx).trim();
        String[] stopTokens = { "コラボエフェクト", "ギフト", "エクストラ" };
        int end = trimmed.length();
        for (String token : stopTokens) {
            int tokenIdx = trimmed.indexOf(token, "ブルームエフェクト".length());
            if (tokenIdx > 0 && tokenIdx < end) {
                end = tokenIdx;
            }
        }
        return trimmed.substring(0, end).trim();
    }

    private List<String> inferBloomEffectTypes(String bloomText) {
        Set<String> effectTypes = new LinkedHashSet<>();
        String text = normalizeDigits(bloomText == null ? "" : bloomText);
        if (!StringUtils.hasText(text)) {
            effectTypes.add("UNIMPLEMENTED");
            return new ArrayList<>(effectTypes);
        }

        if (text.contains("手札に加える")) {
            effectTypes.add("SEARCH");
        }
        if (text.contains("手札に戻")) {
            effectTypes.add("RETURN_TO_HAND");
        }
        if (text.contains("デッキの上に戻")) {
            effectTypes.add("RETURN_TO_DECK_TOP");
        }
        if (text.contains("引く")) {
            effectTypes.add("DRAW");
        }
        if (text.contains("エール") && text.contains("送")) {
            effectTypes.add("ADD_CHEER");
        }
        if (
            text.contains("付け替え")
            || text.contains("割り振って付け")
            || text.contains("付けられる")
            || text.contains("付ける")
        ) {
            effectTypes.add("REATTACH");
        }
        if (text.contains("ステージに出せ") || text.contains("ステージに出す")) {
            effectTypes.add("SUMMON_TO_STAGE");
        }
        if (text.contains("公開し、アーカイブ")) {
            effectTypes.add("REVEAL_TO_ARCHIVE");
        }
        if (text.contains("アーカイブのホロメンを使ってBloom")) {
            effectTypes.add("BLOOM_FROM_ARCHIVE");
        }
        if (text.contains("エールデッキの下に戻")) {
            effectTypes.add("RETURN_CHEER_TO_DECK_BOTTOM");
        }
        if (text.contains("交代できる")) {
            effectTypes.add("SWAP_WITH_COLLAB");
        }
        if (text.contains("移動させる")) {
            effectTypes.add("MOVE_ZONE");
        }
        if (text.contains("回復")) {
            effectTypes.add("HEAL");
        }
        if (text.contains("ダメージ")) {
            effectTypes.add("DAMAGE");
        }
        if (text.contains("アーツ")) {
            if (text.contains("-")) {
                effectTypes.add("DEBUFF");
            } else if (text.contains("+")) {
                effectTypes.add("BUFF");
            }
        }
        if (effectTypes.isEmpty()) {
            effectTypes.add("UNIMPLEMENTED");
        }
        return new ArrayList<>(effectTypes);
    }

    private String inferBloomTargetType(String effectType) {
        return switch (effectType) {
            case "DAMAGE", "DEBUFF", "MOVE_ZONE" -> "ENEMY";
            default -> "SELF";
        };
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

    private int resolveActionCount(JsonNode effectNode, String fallbackToken, int defaultValue) {
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
        if (StringUtils.hasText(fallbackToken) && text.contains(fallbackToken)) {
            return 1;
        }
        return defaultValue;
    }

    private Integer resolveBloomDiceRoll(String bloomText) {
        String text = normalizeDigits(bloomText);
        if (!StringUtils.hasText(text) || !text.contains("サイコロ")) {
            return null;
        }
        return diceService.rollD6();
    }

    private boolean shouldApplyByDice(String rawText, JsonNode effectNode, String effectType) {
        String text = normalizeDigits(rawText);
        if (!StringUtils.hasText(text) || !text.contains("サイコロ")) {
            return true;
        }
        int diceRoll = resolveDiceRoll(effectNode);
        if (diceRoll <= 0) {
            return true;
        }
        if (text.contains("奇数の時") && text.contains("偶数の時")) {
            if ("RETURN_TO_HAND".equals(effectType)) {
                return diceRoll % 2 == 1;
            }
            if ("RETURN_TO_DECK_TOP".equals(effectType)) {
                return diceRoll % 2 == 0;
            }
        }
        Matcher atLeastMatcher = DICE_AT_LEAST_PATTERN.matcher(text);
        if (atLeastMatcher.find()) {
            try {
                return diceRoll >= Integer.parseInt(atLeastMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        Matcher atMostMatcher = DICE_AT_MOST_PATTERN.matcher(text);
        if (atMostMatcher.find()) {
            try {
                return diceRoll <= Integer.parseInt(atMostMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return true;
    }

    private int resolveDiceRoll(JsonNode effectNode) {
        int fromNode = extractInt(effectNode, 0, "diceRoll");
        if (fromNode >= 1 && fromNode <= 6) {
            return fromNode;
        }
        return diceService.rollD6();
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
            } else if (
                rawText.contains("サポート")
                || rawText.contains("ツール")
                || rawText.contains("イベント")
                || rawText.contains("ファン")
                || rawText.contains("マスコット")
            ) {
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
        if (!StringUtils.hasText(nameContains)) {
            Matcher nameTokenMatcher = NAME_TOKEN_PATTERN.matcher(rawText);
            if (nameTokenMatcher.find()) {
                nameContains = nameTokenMatcher.group(1).trim();
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
        return loadCandidatesFromZone(
            matchId,
            userId,
            "DECK",
            cardType,
            levelType,
            tag,
            nameContains,
            false
        );
    }

    private List<Map<String, Object>> loadCandidatesFromZone(
        Long matchId,
        Long userId,
        String zone,
        String cardType,
        String levelType,
        String tag,
        String nameContains,
        boolean excludeLimitedSupport
    ) {
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, c.card_type, m.level_type, c.name, c.tags_json::text AS tags_json
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            LEFT JOIN support_cards sc ON sc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = ?
              AND (? = '' OR c.card_type = ?)
              AND (? = '' OR m.level_type = ?)
              AND (? = '' OR c.name ILIKE '%' || ? || '%')
              AND (? = FALSE OR c.card_type <> 'SUPPORT' OR COALESCE(sc.is_limited, FALSE) = FALSE)
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
            zone,
            nullToEmpty(cardType),
            nullToEmpty(cardType),
            nullToEmpty(levelType),
            nullToEmpty(levelType),
            nullToEmpty(nameContains),
            nullToEmpty(nameContains),
            excludeLimitedSupport,
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

    private String normalizeHolomemLevel(String levelType) {
        String normalized = normalizeLevelType(levelType);
        if ("FIRST".equals(normalized) || "SECOND".equals(normalized) || "SPOT".equals(normalized) || "BUZZ".equals(normalized)) {
            return normalized;
        }
        return "DEBUT";
    }

    private int resolveBloomLevelRank(String levelType) {
        String normalized = normalizeHolomemLevel(levelType);
        return switch (normalized) {
            case "DEBUT" -> 0;
            case "FIRST" -> 1;
            case "SECOND" -> 2;
            case "BUZZ" -> 3;
            default -> -1;
        };
    }

    private String resolveCheerColorFilter(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        if (rawText.contains("赤")) {
            return "RED";
        }
        if (rawText.contains("青")) {
            return "BLUE";
        }
        if (rawText.contains("緑")) {
            return "GREEN";
        }
        if (rawText.contains("白")) {
            return "WHITE";
        }
        if (rawText.contains("紫")) {
            return "PURPLE";
        }
        if (rawText.contains("黄")) {
            return "YELLOW";
        }
        return "";
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

    private String resolveAvailableStageZone(Long matchId, Long userId, String preferredZone) {
        String preferred = normalize(preferredZone);
        int centerCount = countHolomemsInZone(matchId, userId, "CENTER");
        int backCount = countHolomemsInZone(matchId, userId, "BACK");

        if ("CENTER".equals(preferred) && centerCount == 0) {
            return "CENTER";
        }
        if ("BACK".equals(preferred) && backCount < 5) {
            return "BACK";
        }
        if (backCount < 5) {
            return "BACK";
        }
        if (centerCount == 0) {
            return "CENTER";
        }
        return "";
    }

    private int countHolomemsInZone(Long matchId, Long userId, String zone) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        return count == null ? 0 : count;
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

    private void recordHolomemStackCard(Long matchHolomemId, Long matchCardId) {
        if (matchHolomemId == null || matchCardId == null) {
            return;
        }
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(stack_order), 0) + 1
            FROM match_holomem_stack_cards
            WHERE match_holomem_id = ?
            """,
            Integer.class,
            matchHolomemId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, ?)
            ON CONFLICT (match_card_id) DO NOTHING
            """,
            matchHolomemId,
            matchCardId,
            nextOrder == null ? 1 : nextOrder
        );
    }

    private List<Long> archiveHolomemStackCards(Long matchId, Long matchHolomemId, Long ownerUserId) {
        if (matchHolomemId == null || ownerUserId == null) {
            return List.of();
        }
        List<Long> stackCardInstanceIds = jdbcTemplate.query(
            """
            SELECT s.match_card_id
            FROM match_holomem_stack_cards s
            JOIN match_cards mc ON mc.id = s.match_card_id
            WHERE s.match_holomem_id = ?
              AND mc.match_id = ?
              AND mc.owner_user_id = ?
            ORDER BY s.stack_order DESC, s.id DESC
            """,
            (rs, rowNum) -> rs.getLong("match_card_id"),
            matchHolomemId,
            matchId,
            ownerUserId
        );
        if (stackCardInstanceIds.isEmpty()) {
            return List.of();
        }
        List<Long> archived = new ArrayList<>();
        for (Long stackCardInstanceId : stackCardInstanceIds) {
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
                """,
                archiveOrder,
                stackCardInstanceId,
                matchId,
                ownerUserId
            );
            if (updated == 1) {
                archived.add(stackCardInstanceId);
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

    private Map<String, Object> findCheerCardFromZone(Long matchId, Long userId, String zone) {
        String normalizedZone = normalize(zone);
        if (!"CHEER_DECK".equals(normalizedZone) && !"ARCHIVE".equals(normalizedZone) && !"STAGE".equals(normalizedZone)) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, mc.zone
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = ?
            ORDER BY mc.order_index NULLS LAST, mc.id
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
            userId,
            normalizedZone
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

    public record DecisionCandidate(
        Long cardInstanceId,
        String cardId,
        String name,
        String cardType,
        String levelType,
        String zone
    ) {}

    public record SupportDecisionPlan(
        String effectType,
        int minSelect,
        int maxSelect,
        List<DecisionCandidate> candidates
    ) {}

    private record SelectionProbe(
        int requestedCount,
        List<DecisionCandidate> candidates
    ) {}
}
