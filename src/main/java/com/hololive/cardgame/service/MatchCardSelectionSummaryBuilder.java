package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.SearchCriteria;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MatchCardSelectionSummaryBuilder {

    Map<String, Object> buildDeckBottomReorderCandidate(Map<String, Object> row, Long cardInstanceId) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("cardInstanceId", cardInstanceId);
        candidate.put("cardId", MatchEffectValueHelper.asText(row.get("card_id")));
        candidate.put("name", MatchEffectValueHelper.asText(row.get("name")));
        candidate.put("cardType", MatchEffectValueHelper.normalize(MatchEffectValueHelper.asText(row.get("card_type"))));
        candidate.put("levelType", normalizeLevelType(MatchEffectValueHelper.asText(row.get("level_type"))));
        candidate.put("zone", "DECK");
        return candidate;
    }

    Map<String, Object> buildSearchEffectSummary(
        String effectType,
        int searchCount,
        List<Map<String, Object>> candidates,
        List<Map<String, Object>> searchPool,
        int lookTopCount,
        String searchSourceZone,
        boolean archiveUnselectedTopWindow,
        List<Long> archivedRemainderCardInstanceIds,
        List<String> archivedRemainderCardIds,
        List<Long> selectedCardInstanceIds,
        List<Long> movedCardInstanceIds,
        List<String> movedCardIds,
        List<Map<String, Object>> reorderCandidates,
        SearchCriteria criteria
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("searchRequested", searchCount);
        summary.put("candidateCount", candidates.size());
        summary.put("searchPoolCount", searchPool.size());
        summary.put("lookTopCount", lookTopCount);
        summary.put("searchSourceZone", searchSourceZone);
        summary.put("searchApplied", movedCardInstanceIds.size());
        summary.put("archiveUnselectedTopWindow", archiveUnselectedTopWindow);
        summary.put("archiveRemainderApplied", archivedRemainderCardInstanceIds.size());
        summary.put("archiveRemainderCardInstanceIds", archivedRemainderCardInstanceIds);
        summary.put("archiveRemainderCardIds", archivedRemainderCardIds);
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("searchedCardInstanceIds", movedCardInstanceIds);
        summary.put("searchedCardIds", movedCardIds);
        summary.put("requiresDeckBottomReorder", !reorderCandidates.isEmpty());
        summary.put(
            "deckBottomReorderCandidateCardInstanceIds",
            reorderCandidates.stream()
                .map(row -> MatchEffectValueHelper.asLong(row.get("cardInstanceId")))
                .filter(id -> id != null && id > 0)
                .toList()
        );
        summary.put("deckBottomReorderCandidates", reorderCandidates);
        summary.put("criteria", buildCriteriaSummary(criteria));
        return summary;
    }

    Map<String, Object> buildReturnToHandSummary(
        String effectType,
        int returnCount,
        List<Map<String, Object>> candidates,
        List<Long> movedCardInstanceIds,
        List<String> movedCardIds,
        List<Long> selectedCardInstanceIds,
        SearchCriteria criteria,
        boolean excludeLimitedSupport,
        String sourceZone
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("returnRequested", returnCount);
        summary.put("candidateCount", candidates.size());
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        Map<String, Object> criteriaSummary = buildCriteriaSummary(criteria);
        criteriaSummary.put("excludeLimitedSupport", excludeLimitedSupport);
        criteriaSummary.put("sourceZone", sourceZone);
        summary.put("criteria", criteriaSummary);
        return summary;
    }

    Map<String, Object> buildReturnToDeckTopSummary(
        String effectType,
        int returnCount,
        List<Map<String, Object>> candidates,
        List<Long> movedCardInstanceIds,
        List<String> movedCardIds,
        List<Long> selectedCardInstanceIds,
        SearchCriteria criteria
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("returnRequested", returnCount);
        summary.put("candidateCount", candidates.size());
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        summary.put("criteria", buildCriteriaSummary(criteria));
        return summary;
    }

    Map<String, Object> buildCriteriaSummary(SearchCriteria criteria) {
        SearchCriteria resolved = criteria == null ? SearchCriteria.empty() : criteria;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cardType", resolved.cardType());
        summary.put("levelType", resolved.levelType());
        summary.put("tag", resolved.tag());
        summary.put("nameContains", resolved.nameContains());
        summary.put("color", resolved.color());
        summary.put("rested", resolved.rested());
        summary.put("minRemainHp", resolved.minRemainHp());
        summary.put("maxRemainHp", resolved.maxRemainHp());
        if (!resolved.allOf().isEmpty()) {
            List<Map<String, Object>> allOfSummaries = new ArrayList<>();
            for (SearchCriteria sub : resolved.allOf()) {
                allOfSummaries.add(buildCriteriaSummary(sub));
            }
            summary.put("allOf", allOfSummaries);
        }
        if (!resolved.anyOf().isEmpty()) {
            List<Map<String, Object>> anyOfSummaries = new ArrayList<>();
            for (SearchCriteria sub : resolved.anyOf()) {
                anyOfSummaries.add(buildCriteriaSummary(sub));
            }
            summary.put("anyOf", anyOfSummaries);
        }
        return summary;
    }

    private String normalizeLevelType(String levelType) {
        String normalized = MatchEffectValueHelper.normalize(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }
}
