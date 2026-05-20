package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MatchCardSelectionExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchCardSelectionRequestResolver requestResolver = new MatchCardSelectionRequestResolver(effectTextParser);
    private final SearchCriteriaParser criteriaParser = new SearchCriteriaParser(null, effectTextParser);
    private final FakeCandidateProvider candidateProvider = new FakeCandidateProvider();
    private final MatchCardSelectionProbeBuilder probeBuilder = new MatchCardSelectionProbeBuilder(
        effectTextParser,
        criteriaParser,
        requestResolver,
        candidateProvider,
        (rawText, effectNode, effectType) -> true
    );

    @Test
    void executeReturnToHandEffectShouldReturnNoOpWhenDiceConditionMisses() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchCardSelectionExecutionService service = new MatchCardSelectionExecutionService(
            jdbcTemplate,
            effectTextParser,
            criteriaParser,
            requestResolver,
            probeBuilder,
            new MatchCardSelectionSummaryBuilder(),
            candidateProvider,
            (rawText, effectNode, effectType) -> false
        );

        Map<String, Object> summary = service.executeReturnToHandEffect(
            1L,
            2L,
            "RETURN_TO_HAND",
            objectMapper.readTree("{\"rawText\":\"サイコロを振り、条件未命中なら手札に戻す\"}"),
            List.of(101L)
        );

        assertThat(summary)
            .containsEntry("effectType", "RETURN_TO_HAND")
            .containsEntry("applied", false)
            .containsEntry("reason", "骰子條件未命中");
        verifyNoInteractions(jdbcTemplate);
    }

    private static final class FakeCandidateProvider implements MatchCardSelectionProbeBuilder.CandidateProvider {

        @Override
        public List<Map<String, Object>> loadSearchCandidates(Long matchId, Long userId, SearchCriteria criteria) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> loadTopDeckWindow(Long matchId, Long userId, int count) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> loadCandidatesFromZone(
            Long matchId,
            Long userId,
            String zone,
            SearchCriteria criteria,
            boolean excludeLimitedSupport
        ) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> loadCandidatesByCardInstanceIds(
            Long matchId,
            Long userId,
            List<Long> cardInstanceIds,
            SearchCriteria criteria
        ) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> filterCandidatesByCriteria(List<Map<String, Object>> rows, SearchCriteria criteria) {
            return rows;
        }

        @Override
        public List<Map<String, Object>> selectSearchCards(
            List<Map<String, Object>> candidates,
            List<Long> selectedCardInstanceIds,
            int searchCount
        ) {
            return List.of();
        }
    }
}
