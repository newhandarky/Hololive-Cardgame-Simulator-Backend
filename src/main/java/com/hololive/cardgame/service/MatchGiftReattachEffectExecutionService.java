package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchGiftReattachEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver;
    private final DiceApplicabilityChecker diceApplicabilityChecker;
    private final NoOpEffectExecutor noOpEffectExecutor;
    private final OpponentUserResolver opponentUserResolver;
    private final GiftHolderHolomemResolver giftHolderHolomemResolver;
    private final MatchAddCheerTargetResolverService addCheerTargetResolverService;
    private final HolomemOwnerResolver holomemOwnerResolver;
    private final TargetHolomemResolver targetHolomemResolver;
    private final MatchCheerCandidateQueryService cheerCandidateQueryService;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchGiftReattachEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        MatchCardSelectionRequestResolver cardSelectionRequestResolver,
        DiceApplicabilityChecker diceApplicabilityChecker,
        NoOpEffectExecutor noOpEffectExecutor,
        OpponentUserResolver opponentUserResolver,
        GiftHolderHolomemResolver giftHolderHolomemResolver,
        MatchAddCheerTargetResolverService addCheerTargetResolverService,
        HolomemOwnerResolver holomemOwnerResolver,
        TargetHolomemResolver targetHolomemResolver,
        MatchCheerCandidateQueryService cheerCandidateQueryService,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.cardSelectionRequestResolver = cardSelectionRequestResolver;
        this.diceApplicabilityChecker = diceApplicabilityChecker;
        this.noOpEffectExecutor = noOpEffectExecutor;
        this.opponentUserResolver = opponentUserResolver;
        this.giftHolderHolomemResolver = giftHolderHolomemResolver;
        this.addCheerTargetResolverService = addCheerTargetResolverService;
        this.holomemOwnerResolver = holomemOwnerResolver;
        this.targetHolomemResolver = targetHolomemResolver;
        this.cheerCandidateQueryService = cheerCandidateQueryService;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

    Map<String, Object> executeReattachEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceApplicabilityChecker.shouldApply(rawText, effectNode, effectType)) {
            return noOpEffectExecutor.execute(effectType, effectNode, "骰子條件未命中");
        }
        if (!rawText.contains("エール")) {
            return noOpEffectExecutor.execute(effectType, effectNode, "目前僅支援 Cheer 的付け/付け替え");
        }

        String normalizedTargetType = normalize(targetType);
        boolean opponentContext =
            isOpponentTargetType(normalizedTargetType)
                || rawText.contains("相手のステージ")
                || rawText.contains("相手のアーカイブ")
                || rawText.contains("相手のエールデッキ");
        Long sourceOwnerUserId = opponentContext ? opponentUserResolver.resolve(matchId, userId) : userId;
        if (sourceOwnerUserId == null) {
            return noOpEffectExecutor.execute(effectType, effectNode, "找不到可操作的玩家");
        }
        String effectiveTargetType = opponentContext ? "ENEMY" : targetType;
        Long holderHolomemId = giftHolderHolomemResolver.resolve(
            matchId,
            sourceOwnerUserId,
            targetHolomemCardInstanceId,
            effectNode
        );
        Long targetHolomemId = addCheerTargetResolverService.resolvePreferredAddCheerTargetHolomemId(
            matchId,
            sourceOwnerUserId,
            effectiveTargetType,
            targetHolomemCardInstanceId,
            rawText,
            false,
            holderHolomemId
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("REATTACH 找不到目標 Holomen");
        }
        Long targetOwnerUserId = holomemOwnerResolver.resolve(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("REATTACH 結算失敗：找不到目標擁有者");
        }
        if (!targetOwnerUserId.equals(sourceOwnerUserId)) {
            targetHolomemId = targetHolomemResolver.resolve(matchId, sourceOwnerUserId, null);
            if (targetHolomemId == null) {
                throw new IllegalStateException("REATTACH 結算失敗：找不到可附加的目標 Holomen");
            }
            targetOwnerUserId = sourceOwnerUserId;
        }

        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "付け", 1);
        int moveCount = Math.max(requestedCount, 1);

        List<String> movedCheerCardIds = new ArrayList<>();
        List<Long> movedCheerRowIds = new ArrayList<>();
        String sourceMode;
        if (rawText.contains("アーカイブ")) {
            sourceMode = "ARCHIVE";
            for (int i = 0; i < moveCount; i++) {
                Map<String, Object> archivedCheer = cheerCandidateQueryService.findCheerCardFromZone(
                    matchId,
                    sourceOwnerUserId,
                    "ARCHIVE"
                );
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
                    INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cardInstanceId,
                    cheerCardId
                );
                movedCheerCardIds.add(cheerCardId);
                if (cheerRowId != null) {
                    movedCheerRowIds.add(cheerRowId);
                }
            }
        } else {
            sourceMode = "STAGE";
            boolean restrictToHolderCheer = rawText.contains("このホロメンのエール");
            if (restrictToHolderCheer) {
                List<Map<String, Object>> holderCheerRows = resolvePreferredReattachSourceRows(
                    matchId,
                    sourceOwnerUserId,
                    holderHolomemId,
                    effectNode
                );
                String holderCheerSourceMode = moveSpecificCheerRowsToHolomem(
                    matchId,
                    sourceOwnerUserId,
                    targetHolomemId,
                    holderCheerRows,
                    moveCount,
                    movedCheerCardIds,
                    movedCheerRowIds
                );
                if (StringUtils.hasText(holderCheerSourceMode)) {
                    sourceMode = holderCheerSourceMode;
                }
                return buildSummary(
                    effectType,
                    moveCount,
                    targetHolomemId,
                    movedCheerCardIds,
                    movedCheerRowIds,
                    StringUtils.hasText(sourceMode) ? sourceMode : "HOLDER_CHEER"
                );
            }
            List<Map<String, Object>> attachedRows = jdbcTemplate.queryForList(
                """
                SELECT c.id AS cheer_row_id,
                       c.match_card_id,
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
                Long cheerCardInstanceId = asLong(row.get("match_card_id"));
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
                    INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cheerCardInstanceId,
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
                    Map<String, Object> cheerDeckTop = cheerCandidateQueryService.findCheerCardFromZone(
                        matchId,
                        sourceOwnerUserId,
                        "CHEER_DECK"
                    );
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
                        INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                        VALUES (?, ?, ?, FALSE)
                        RETURNING id
                        """,
                        rs -> rs.next() ? rs.getLong("id") : null,
                        targetHolomemId,
                        cardInstanceId,
                        cheerCardId
                    );
                    movedCheerCardIds.add(cheerCardId);
                    if (newCheerRowId != null) {
                        movedCheerRowIds.add(newCheerRowId);
                    }
                }
            }
        }

        return buildSummary(effectType, moveCount, targetHolomemId, movedCheerCardIds, movedCheerRowIds, sourceMode);
    }

    private Map<String, Object> buildSummary(
        String effectType,
        int moveCount,
        Long targetHolomemId,
        List<String> movedCheerCardIds,
        List<Long> movedCheerRowIds,
        String sourceMode
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("moveRequested", moveCount);
        summary.put("moveApplied", movedCheerCardIds.size());
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
        summary.put("movedCheerCardIds", movedCheerCardIds);
        summary.put("movedCheerRowIds", movedCheerRowIds);
        summary.put("sourceMode", sourceMode);
        return summary;
    }

    private List<Map<String, Object>> resolvePreferredReattachSourceRows(
        Long matchId,
        Long ownerUserId,
        Long holderHolomemId,
        JsonNode effectNode
    ) {
        List<Long> storedCheerCardInstanceIds = MatchEffectValueHelper.extractEffectNodeLongList(
            effectNode,
            "giftHolderAttachedCheerCardInstanceIds"
        );
        if (!storedCheerCardInstanceIds.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Long storedCheerCardInstanceId : storedCheerCardInstanceIds) {
                if (storedCheerCardInstanceId == null || storedCheerCardInstanceId <= 0) {
                    continue;
                }
                Map<String, Object> row = jdbcTemplate.query(
                    """
                    SELECT c.id AS cheer_row_id,
                           mc.id AS match_card_id,
                           mc.card_id AS cheer_card_id,
                           c.match_holomem_id,
                           mc.zone
                    FROM match_cards mc
                    LEFT JOIN match_holomem_cheers c ON c.match_card_id = mc.id
                    WHERE mc.match_id = ?
                      AND mc.owner_user_id = ?
                      AND mc.id = ?
                    LIMIT 1
                    """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("cheer_row_id", asLong(rs.getObject("cheer_row_id")));
                        result.put("match_card_id", asLong(rs.getObject("match_card_id")));
                        result.put("cheer_card_id", rs.getString("cheer_card_id"));
                        result.put("match_holomem_id", asLong(rs.getObject("match_holomem_id")));
                        result.put("zone", rs.getString("zone"));
                        return result;
                    },
                    matchId,
                    ownerUserId,
                    storedCheerCardInstanceId
                );
                if (row != null && !row.isEmpty()) {
                    rows.add(row);
                }
            }
            return rows;
        }
        if (holderHolomemId == null || holderHolomemId <= 0) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
            """
            SELECT c.id AS cheer_row_id,
                   mc.id AS match_card_id,
                   mc.card_id AS cheer_card_id,
                   c.match_holomem_id,
                   mc.zone
            FROM match_holomem_cheers c
            JOIN match_cards mc ON mc.id = c.match_card_id
            WHERE c.match_holomem_id = ?
            ORDER BY c.id
            """,
            holderHolomemId
        );
    }

    private String moveSpecificCheerRowsToHolomem(
        Long matchId,
        Long ownerUserId,
        Long targetHolomemId,
        List<Map<String, Object>> candidateRows,
        int moveCount,
        List<String> movedCheerCardIds,
        List<Long> movedCheerRowIds
    ) {
        String sourceMode = null;
        if (candidateRows == null || candidateRows.isEmpty()) {
            return sourceMode;
        }
        for (Map<String, Object> row : candidateRows) {
            if (movedCheerCardIds.size() >= moveCount) {
                break;
            }
            Long cheerCardInstanceId = asLong(row.get("match_card_id"));
            String cheerCardId = asText(row.get("cheer_card_id"));
            String currentZone = normalize(asText(row.get("zone")));
            if (cheerCardInstanceId == null || cheerCardInstanceId <= 0 || !StringUtils.hasText(cheerCardId)) {
                continue;
            }
            if ("ARCHIVE".equals(currentZone)) {
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
                    cheerCardInstanceId,
                    matchId,
                    ownerUserId
                );
                if (moved != 1) {
                    continue;
                }
                Long newCheerRowId = jdbcTemplate.query(
                    """
                    INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cheerCardInstanceId,
                    cheerCardId
                );
                movedCheerCardIds.add(cheerCardId);
                if (newCheerRowId != null) {
                    movedCheerRowIds.add(newCheerRowId);
                }
                sourceMode = mergeSourceMode(sourceMode, "ARCHIVE");
                continue;
            }
            if (!"STAGE".equals(currentZone)) {
                continue;
            }
            jdbcTemplate.update(
                """
                DELETE FROM match_holomem_cheers c
                USING match_holomems h
                WHERE c.match_holomem_id = h.id
                  AND c.match_card_id = ?
                  AND h.match_id = ?
                  AND h.owner_user_id = ?
                """,
                cheerCardInstanceId,
                matchId,
                ownerUserId
            );
            Long newCheerRowId = jdbcTemplate.query(
                """
                INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                VALUES (?, ?, ?, FALSE)
                RETURNING id
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                targetHolomemId,
                cheerCardInstanceId,
                cheerCardId
            );
            movedCheerCardIds.add(cheerCardId);
            if (newCheerRowId != null) {
                movedCheerRowIds.add(newCheerRowId);
            }
            sourceMode = mergeSourceMode(sourceMode, "STAGE");
        }
        return sourceMode;
    }

    private boolean isOpponentTargetType(String targetType) {
        return targetType.contains("ENEMY") || targetType.contains("OPPONENT");
    }

    private String mergeSourceMode(String current, String next) {
        if (!StringUtils.hasText(next)) {
            return current;
        }
        if (!StringUtils.hasText(current)) {
            return next;
        }
        if (current.equals(next)) {
            return current;
        }
        return "MIXED";
    }

    private String normalize(Object value) {
        return MatchEffectValueHelper.normalize(value);
    }

    private Long asLong(Object value) {
        return MatchEffectValueHelper.asLong(value);
    }

    private String asText(Object value) {
        return MatchEffectValueHelper.asText(value);
    }

    @FunctionalInterface
    interface DiceApplicabilityChecker {
        boolean shouldApply(String rawText, JsonNode effectNode, String effectType);
    }

    @FunctionalInterface
    interface NoOpEffectExecutor {
        Map<String, Object> execute(String effectType, JsonNode effectNode, String reason);
    }

    @FunctionalInterface
    interface OpponentUserResolver {
        Long resolve(Long matchId, Long userId);
    }

    @FunctionalInterface
    interface GiftHolderHolomemResolver {
        Long resolve(Long matchId, Long ownerUserId, Long holderCardInstanceId, JsonNode effectNode);
    }

    @FunctionalInterface
    interface HolomemOwnerResolver {
        Long resolve(Long matchId, Long holomemId);
    }

    @FunctionalInterface
    interface TargetHolomemResolver {
        Long resolve(Long matchId, Long userId, Long targetHolomemCardInstanceId);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
