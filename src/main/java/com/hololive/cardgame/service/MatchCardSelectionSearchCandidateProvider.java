package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.SearchCriteria;
import java.util.List;
import java.util.Map;

final class MatchCardSelectionSearchCandidateProvider implements MatchCardSelectionProbeBuilder.CandidateProvider {

    private final MatchEffectSearchService searchService;

    MatchCardSelectionSearchCandidateProvider(MatchEffectSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public List<Map<String, Object>> loadSearchCandidates(Long matchId, Long userId, SearchCriteria criteria) {
        return searchService.loadSearchCandidates(matchId, userId, criteria);
    }

    @Override
    public List<Map<String, Object>> loadTopDeckWindow(Long matchId, Long userId, int count) {
        return searchService.loadTopDeckWindow(matchId, userId, count);
    }

    @Override
    public List<Map<String, Object>> loadCandidatesFromZone(
        Long matchId,
        Long userId,
        String zone,
        SearchCriteria criteria,
        boolean excludeLimitedSupport
    ) {
        return searchService.loadCandidatesFromZone(matchId, userId, zone, criteria, excludeLimitedSupport);
    }

    @Override
    public List<Map<String, Object>> loadCandidatesByCardInstanceIds(
        Long matchId,
        Long userId,
        List<Long> cardInstanceIds,
        SearchCriteria criteria
    ) {
        return searchService.loadCandidatesByCardInstanceIds(matchId, userId, cardInstanceIds, criteria);
    }

    @Override
    public List<Map<String, Object>> filterCandidatesByCriteria(List<Map<String, Object>> rows, SearchCriteria criteria) {
        return searchService.filterCandidatesByCriteria(rows, criteria);
    }
}
