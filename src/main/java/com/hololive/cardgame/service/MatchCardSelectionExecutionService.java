package com.hololive.cardgame.service;

import static com.hololive.cardgame.service.MatchEffectValueHelper.asLong;
import static com.hololive.cardgame.service.MatchEffectValueHelper.asText;
import static com.hololive.cardgame.service.MatchEffectValueHelper.extractEffectNodeLongList;
import static com.hololive.cardgame.service.MatchEffectValueHelper.readBoolean;
import static com.hololive.cardgame.service.MatchEffectValueHelper.toBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchCardSelectionExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchCardSelectionRequestResolver requestResolver;
    private final MatchCardSelectionProbeBuilder probeBuilder;
    private final MatchCardSelectionSummaryBuilder summaryBuilder;
    private final MatchCardSelectionProbeBuilder.CandidateProvider candidateProvider;
    private final MatchCardSelectionProbeBuilder.DiceConditionChecker diceConditionChecker;

    MatchCardSelectionExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        SearchCriteriaParser searchCriteriaParser,
        MatchCardSelectionRequestResolver requestResolver,
        MatchCardSelectionProbeBuilder probeBuilder,
        MatchCardSelectionSummaryBuilder summaryBuilder,
        MatchCardSelectionProbeBuilder.CandidateProvider candidateProvider,
        MatchCardSelectionProbeBuilder.DiceConditionChecker diceConditionChecker
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.searchCriteriaParser = searchCriteriaParser;
        this.requestResolver = requestResolver;
        this.probeBuilder = probeBuilder;
        this.summaryBuilder = summaryBuilder;
        this.candidateProvider = candidateProvider;
        this.diceConditionChecker = diceConditionChecker;
    }

    Map<String, Object> executeSearchEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        String searchSourceZone = requestResolver.resolveSearchSourceZone(effectNode, rawText);
        boolean searchFromDeck = "DECK".equals(searchSourceZone);
        int requestedCount = requestResolver.resolveSearchCount(effectNode);
        int searchCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        int lookTopCount = requestResolver.resolveSearchLookTopCount(effectNode, rawText);
        boolean archiveUnselectedTopWindow = toBoolean(
            readBoolean(
                effectNode,
                "archiveUnselectedTopWindow",
                "archiveRemainingTopWindow",
                "archiveRemainder"
            )
        );
        boolean requiresDeckBottomReorder =
            searchFromDeck
                && lookTopCount > 0
                && rawText.contains("好きな順でデッキの下に戻す");

        List<Map<String, Object>> searchPool = searchFromDeck && lookTopCount > 0
            ? candidateProvider.loadTopDeckWindow(matchId, userId, lookTopCount)
            : candidateProvider.loadCandidatesFromZone(matchId, userId, searchSourceZone, criteria, false);
        List<Map<String, Object>> candidates = lookTopCount > 0
            ? candidateProvider.filterCandidatesByCriteria(searchPool, criteria)
            : searchPool;

        List<Map<String, Object>> selected = candidateProvider.selectSearchCards(candidates, selectedCardInstanceIds, searchCount);
        MovedCards movedCards = moveSelectedCardsToHand(matchId, userId, selected, searchSourceZone);
        Set<Long> selectedIds = collectCardInstanceIds(selected);

        List<Map<String, Object>> reorderCandidates = new ArrayList<>();
        MovedCards archivedRemainderCards = new MovedCards();
        if (archiveUnselectedTopWindow && searchFromDeck && lookTopCount > 0) {
            archivedRemainderCards = moveUnselectedTopWindowCardsToArchive(matchId, userId, searchPool, selectedIds);
        } else if (requiresDeckBottomReorder) {
            for (Map<String, Object> row : searchPool) {
                Long id = asLong(row.get("id"));
                if (id == null || selectedIds.contains(id)) {
                    continue;
                }
                reorderCandidates.add(summaryBuilder.buildDeckBottomReorderCandidate(row, id));
            }
            if (reorderCandidates.size() == 1) {
                Long onlyCardInstanceId = asLong(reorderCandidates.get(0).get("cardInstanceId"));
                moveDeckCardToBottom(matchId, userId, onlyCardInstanceId);
                reorderCandidates.clear();
            }
        }

        return summaryBuilder.buildSearchEffectSummary(
            effectType,
            searchCount,
            candidates,
            searchPool,
            lookTopCount,
            searchSourceZone,
            archiveUnselectedTopWindow,
            archivedRemainderCards.cardInstanceIds(),
            archivedRemainderCards.cardIds(),
            selectedCardInstanceIds,
            movedCards.cardInstanceIds(),
            movedCards.cardIds(),
            reorderCandidates,
            criteria
        );
    }

    Map<String, Object> executeReturnToHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, "骰子條件未命中");
        }
        int requestedCount = requestResolver.resolveActionCount(effectNode, "手札に戻", 1);
        int returnCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        boolean excludeLimitedSupport = rawText.contains("LIMITED以外");
        List<Long> effectiveSelectedCardInstanceIds = selectedCardInstanceIds;
        if (effectiveSelectedCardInstanceIds == null || effectiveSelectedCardInstanceIds.isEmpty()) {
            effectiveSelectedCardInstanceIds = extractEffectNodeLongList(effectNode, "selectedCardInstanceIds");
        }
        List<Map<String, Object>> candidates = probeBuilder.resolveReturnToHandCandidates(
            matchId,
            userId,
            effectNode,
            criteria,
            excludeLimitedSupport
        );
        List<Map<String, Object>> selected = candidateProvider.selectSearchCards(
            candidates,
            effectiveSelectedCardInstanceIds,
            returnCount
        );

        MovedCards movedCards = moveSelectedCardsToHand(matchId, userId, selected, "ARCHIVE");

        return summaryBuilder.buildReturnToHandSummary(
            effectType,
            returnCount,
            candidates,
            movedCards.cardInstanceIds(),
            movedCards.cardIds(),
            effectiveSelectedCardInstanceIds,
            criteria,
            excludeLimitedSupport,
            requestResolver.resolveReturnToHandSourceZone(effectNode, rawText)
        );
    }

    Map<String, Object> executeReturnToDeckTopEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, "骰子條件未命中");
        }
        int requestedCount = requestResolver.resolveActionCount(effectNode, "デッキの上に戻", 1);
        int returnCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);

        List<Map<String, Object>> candidates = candidateProvider.loadCandidatesFromZone(matchId, userId, "ARCHIVE", criteria, false);
        List<Map<String, Object>> selected = candidateProvider.selectSearchCards(candidates, selectedCardInstanceIds, returnCount);

        MovedCards movedCards = moveSelectedCardsToDeckTop(matchId, userId, selected);

        return summaryBuilder.buildReturnToDeckTopSummary(
            effectType,
            returnCount,
            candidates,
            movedCards.cardInstanceIds(),
            movedCards.cardIds(),
            selectedCardInstanceIds,
            criteria
        );
    }

    private MovedCards moveSelectedCardsToHand(
        Long matchId,
        Long userId,
        List<Map<String, Object>> selected,
        String sourceZone
    ) {
        MovedCards movedCards = new MovedCards();
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
                  AND zone = ?
                """,
                nextHandOrder++,
                cardInstanceId,
                matchId,
                userId,
                sourceZone
            );
            movedCards.record(cardInstanceId, cardId, updated == 1);
        }
        return movedCards;
    }

    private MovedCards moveSelectedCardsToDeckTop(
        Long matchId,
        Long userId,
        List<Map<String, Object>> selected
    ) {
        MovedCards movedCards = new MovedCards();
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
            movedCards.record(cardInstanceId, cardId, updated == 1);
        }
        return movedCards;
    }

    private MovedCards moveUnselectedTopWindowCardsToArchive(
        Long matchId,
        Long userId,
        List<Map<String, Object>> searchPool,
        Set<Long> selectedIds
    ) {
        MovedCards movedCards = new MovedCards();
        int nextArchiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
        for (Map<String, Object> row : searchPool) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || selectedIds.contains(cardInstanceId)) {
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
            movedCards.recordAllowMissingCardId(cardInstanceId, cardId, updated == 1);
        }
        return movedCards;
    }

    private Set<Long> collectCardInstanceIds(List<Map<String, Object>> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Long id = asLong(row.get("id"));
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void moveDeckCardToBottom(Long matchId, Long userId, Long cardInstanceId) {
        if (matchId == null || userId == null || cardInstanceId == null || cardInstanceId <= 0) {
            return;
        }
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            matchId,
            userId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET order_index = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            nextOrder == null ? 1 : nextOrder,
            cardInstanceId,
            matchId,
            userId
        );
    }

    private int nextZoneOrder(Long matchId, Long userId, String zone) {
        Integer nextOrder = jdbcTemplate.queryForObject(
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
        return nextOrder == null ? 1 : nextOrder;
    }

    private Map<String, Object> executeNoOpEffect(String effectType, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        return summary;
    }

    static final class MovedCards {

        private final List<Long> cardInstanceIds = new ArrayList<>();
        private final List<String> cardIds = new ArrayList<>();

        void record(Long cardInstanceId, String cardId, boolean moved) {
            if (!moved || cardInstanceId == null || !StringUtils.hasText(cardId)) {
                return;
            }
            cardInstanceIds.add(cardInstanceId);
            cardIds.add(cardId);
        }

        void recordAllowMissingCardId(Long cardInstanceId, String cardId, boolean moved) {
            if (!moved || cardInstanceId == null) {
                return;
            }
            cardInstanceIds.add(cardInstanceId);
            cardIds.add(cardId);
        }

        List<Long> cardInstanceIds() {
            return cardInstanceIds;
        }

        List<String> cardIds() {
            return cardIds;
        }
    }
}
