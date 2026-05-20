package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void movedCardsShouldRecordOnlySuccessfulCardRows() {
        MatchCardSelectionExecutionService.MovedCards movedCards = new MatchCardSelectionExecutionService.MovedCards();

        movedCards.record(101L, "hBP01-001", true);
        movedCards.record(102L, "hBP01-002", false);
        movedCards.record(null, "hBP01-003", true);
        movedCards.record(104L, "", true);

        assertThat(movedCards.cardInstanceIds()).containsExactly(101L);
        assertThat(movedCards.cardIds()).containsExactly("hBP01-001");
    }

    @Test
    void executeReturnToDeckTopEffectShouldReportOnlyMovedCards() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(2L))).thenReturn(9);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1, 0);
        candidateProvider.candidates = List.of(
            cardRow(201L, "hBP01-201"),
            cardRow(202L, "hBP01-202")
        );
        candidateProvider.selectedCards = candidateProvider.candidates;
        MatchCardSelectionExecutionService service = service(jdbcTemplate, true);

        Map<String, Object> summary = service.executeReturnToDeckTopEffect(
            1L,
            2L,
            "RETURN_TO_DECK_TOP",
            objectMapper.readTree("{\"rawText\":\"アーカイブから2枚デッキの上に戻す\"}"),
            List.of(201L, 202L)
        );

        assertThat(summary)
            .containsEntry("returnApplied", 1)
            .containsEntry("selectedByClient", true);
        assertThat(summary.get("returnedCardInstanceIds")).isEqualTo(List.of(201L));
        assertThat(summary.get("returnedCardIds")).isEqualTo(List.of("hBP01-201"));
    }

    private MatchCardSelectionExecutionService service(JdbcTemplate jdbcTemplate, boolean diceResult) {
        return new MatchCardSelectionExecutionService(
            jdbcTemplate,
            effectTextParser,
            criteriaParser,
            requestResolver,
            probeBuilder,
            new MatchCardSelectionSummaryBuilder(),
            candidateProvider,
            (rawText, effectNode, effectType) -> diceResult
        );
    }

    private Map<String, Object> cardRow(Long id, String cardId) {
        return Map.of(
            "id", id,
            "card_id", cardId
        );
    }

    private static final class FakeCandidateProvider implements MatchCardSelectionProbeBuilder.CandidateProvider {

        private List<Map<String, Object>> candidates = List.of();
        private List<Map<String, Object>> selectedCards = List.of();

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
            return candidates;
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
            return selectedCards;
        }
    }
}
