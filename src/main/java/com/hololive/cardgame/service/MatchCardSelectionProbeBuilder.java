package com.hololive.cardgame.service;

import static com.hololive.cardgame.service.MatchEffectValueHelper.asLong;
import static com.hololive.cardgame.service.MatchEffectValueHelper.asText;
import static com.hololive.cardgame.service.MatchEffectValueHelper.extractEffectNodeLongList;
import static com.hololive.cardgame.service.MatchEffectValueHelper.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

final class MatchCardSelectionProbeBuilder {

    private final EffectTextParser effectTextParser;
    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchCardSelectionRequestResolver requestResolver;
    private final CandidateProvider candidateProvider;
    private final DiceConditionChecker diceConditionChecker;

    MatchCardSelectionProbeBuilder(
        EffectTextParser effectTextParser,
        SearchCriteriaParser searchCriteriaParser,
        MatchCardSelectionRequestResolver requestResolver,
        CandidateProvider candidateProvider,
        DiceConditionChecker diceConditionChecker
    ) {
        this.effectTextParser = effectTextParser;
        this.searchCriteriaParser = searchCriteriaParser;
        this.requestResolver = requestResolver;
        this.candidateProvider = candidateProvider;
        this.diceConditionChecker = diceConditionChecker;
    }

    SelectionProbe probeSelectionCandidates(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String normalizedType = effectTextParser.normalizeEffectType(effectType);
        return switch (normalizedType) {
            case "SEARCH" -> probeSearchCandidates(matchId, userId, effectNode);
            case "RETURN_TO_HAND" -> probeReturnToHandCandidates(matchId, userId, effectNode);
            case "RETURN_TO_DECK_TOP" -> probeReturnToDeckTopCandidates(matchId, userId, effectNode);
            default -> null;
        };
    }

    SelectionProbe probeSearchCandidates(Long matchId, Long userId, JsonNode effectNode) {
        int requestedCount = Math.max(requestResolver.resolveSearchCount(effectNode), 1);
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        int lookTopCount = requestResolver.resolveSearchLookTopCount(effectNode, rawText);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        List<Map<String, Object>> rows = lookTopCount > 0
            ? candidateProvider.filterCandidatesByCriteria(candidateProvider.loadTopDeckWindow(matchId, userId, lookTopCount), criteria)
            : candidateProvider.loadSearchCandidates(matchId, userId, criteria);
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, "DECK")
        );
    }

    SelectionProbe probeReturnToHandCandidates(Long matchId, Long userId, JsonNode effectNode) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, "RETURN_TO_HAND")) {
            return null;
        }
        int requestedCount = Math.max(requestResolver.resolveActionCount(effectNode, "手札に戻", 1), 1);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        boolean excludeLimitedSupport = rawText.contains("LIMITED以外");
        List<Map<String, Object>> rows = resolveReturnToHandCandidates(
            matchId,
            userId,
            effectNode,
            criteria,
            excludeLimitedSupport
        );
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, requestResolver.resolveReturnToHandSourceZone(effectNode, rawText))
        );
    }

    SelectionProbe probeReturnToDeckTopCandidates(Long matchId, Long userId, JsonNode effectNode) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!diceConditionChecker.shouldApply(rawText, effectNode, "RETURN_TO_DECK_TOP")) {
            return null;
        }
        int requestedCount = Math.max(requestResolver.resolveActionCount(effectNode, "デッキの上に戻", 1), 1);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        List<Map<String, Object>> rows = candidateProvider.loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria,
            false
        );
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, "ARCHIVE")
        );
    }

    List<Map<String, Object>> resolveReturnToHandCandidates(
        Long matchId,
        Long userId,
        JsonNode effectNode,
        SearchCriteria criteria,
        boolean excludeLimitedSupport
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (requestResolver.usesGiftHolderStackSnapshotForReturnToHand(effectNode, rawText)) {
            return candidateProvider.loadCandidatesByCardInstanceIds(
                matchId,
                userId,
                extractEffectNodeLongList(effectNode, "giftHolderStackCardInstanceIds"),
                criteria
            );
        }
        return candidateProvider.loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria,
            excludeLimitedSupport
        );
    }

    List<MatchEffectService.DecisionCandidate> mapDecisionCandidates(List<Map<String, Object>> rows, String zone) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<MatchEffectService.DecisionCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            candidates.add(
                new MatchEffectService.DecisionCandidate(
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

    private String normalizeLevelType(String levelType) {
        String normalized = normalize(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }

    interface CandidateProvider {
        List<Map<String, Object>> loadSearchCandidates(Long matchId, Long userId, SearchCriteria criteria);

        List<Map<String, Object>> loadTopDeckWindow(Long matchId, Long userId, int count);

        List<Map<String, Object>> loadCandidatesFromZone(
            Long matchId,
            Long userId,
            String zone,
            SearchCriteria criteria,
            boolean excludeLimitedSupport
        );

        List<Map<String, Object>> loadCandidatesByCardInstanceIds(
            Long matchId,
            Long userId,
            List<Long> cardInstanceIds,
            SearchCriteria criteria
        );

        List<Map<String, Object>> filterCandidatesByCriteria(List<Map<String, Object>> rows, SearchCriteria criteria);
    }

    interface DiceConditionChecker {
        boolean shouldApply(String rawText, JsonNode effectNode, String effectType);
    }

    record SelectionProbe(
        int requestedCount,
        List<MatchEffectService.DecisionCandidate> candidates
    ) {}
}
