package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.MatchCardSelectionProbeBuilder.SelectionProbe;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchCardSelectionProbeBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchCardSelectionRequestResolver requestResolver = new MatchCardSelectionRequestResolver(effectTextParser);
    private final SearchCriteriaParser criteriaParser = new SearchCriteriaParser(null, effectTextParser);

    @Test
    void probeSearchCandidatesShouldLoadDeckCandidatesAndMapDecisionPayload() throws Exception {
        FakeCandidateProvider provider = new FakeCandidateProvider();
        provider.searchRows = List.of(row(101L, "HBP99-001", "搜尋候選", "member", "1st"));
        MatchCardSelectionProbeBuilder builder = builder(provider);

        SelectionProbe probe = builder.probeSelectionCandidates(1L, 2L, "SEARCH", node(
            "{\"value\":2,\"rawText\":\"デッキから2枚手札に加える\"}"
        ));

        assertThat(probe.requestedCount()).isEqualTo(2);
        assertThat(provider.calls).containsExactly("loadSearchCandidates");
        assertThat(probe.candidates()).hasSize(1);
        MatchEffectService.DecisionCandidate candidate = probe.candidates().get(0);
        assertThat(candidate.cardInstanceId()).isEqualTo(101L);
        assertThat(candidate.cardId()).isEqualTo("HBP99-001");
        assertThat(candidate.cardType()).isEqualTo("MEMBER");
        assertThat(candidate.levelType()).isEqualTo("FIRST");
        assertThat(candidate.zone()).isEqualTo("DECK");
    }

    @Test
    void probeSearchCandidatesShouldUseLookTopWindowBeforeFiltering() throws Exception {
        FakeCandidateProvider provider = new FakeCandidateProvider();
        provider.topDeckRows = List.of(row(201L, "HBP99-002", "牌庫頂候選", "member", "Debut"));
        provider.filteredRows = provider.topDeckRows;
        MatchCardSelectionProbeBuilder builder = builder(provider);

        SelectionProbe probe = builder.probeSelectionCandidates(1L, 2L, "SEARCH", node(
            "{\"rawText\":\"デッキの上から3枚を見る。その中から1枚手札に加える\"}"
        ));

        assertThat(probe.requestedCount()).isEqualTo(3);
        assertThat(provider.calls).containsExactly("loadTopDeckWindow:3", "filterCandidatesByCriteria");
        assertThat(probe.candidates()).extracting(MatchEffectService.DecisionCandidate::zone).containsExactly("DECK");
    }

    @Test
    void probeReturnToHandCandidatesShouldLoadArchiveAndKeepExcludeLimitedSupport() throws Exception {
        FakeCandidateProvider provider = new FakeCandidateProvider();
        provider.zoneRows = List.of(row(301L, "HBP99-003", "回手候選", "support", ""));
        MatchCardSelectionProbeBuilder builder = builder(provider);

        SelectionProbe probe = builder.probeSelectionCandidates(1L, 2L, "RETURN_TO_HAND", node(
            "{\"rawText\":\"アーカイブからLIMITED以外のサポート1枚を手札に戻す\"}"
        ));

        assertThat(probe.requestedCount()).isEqualTo(1);
        assertThat(provider.calls).containsExactly("loadCandidatesFromZone:ARCHIVE:true");
        assertThat(probe.candidates()).extracting(MatchEffectService.DecisionCandidate::zone).containsExactly("ARCHIVE");
    }

    @Test
    void probeReturnToHandCandidatesShouldUseGiftHolderStackSnapshotSource() throws Exception {
        FakeCandidateProvider provider = new FakeCandidateProvider();
        provider.idRows = List.of(row(401L, "HBP99-004", "stack 候選", "member", "2nd"));
        MatchCardSelectionProbeBuilder builder = builder(provider);

        SelectionProbe probe = builder.probeSelectionCandidates(1L, 2L, "RETURN_TO_HAND", node(
            "{\"rawText\":\"重なっているホロメンを1枚手札に戻す\",\"giftHolderStackCardInstanceIds\":[401,402]}"
        ));

        assertThat(probe.requestedCount()).isEqualTo(1);
        assertThat(provider.calls).containsExactly("loadCandidatesByCardInstanceIds:2");
        assertThat(probe.candidates()).extracting(MatchEffectService.DecisionCandidate::zone)
            .containsExactly("GIFT_HOLDER_STACK");
    }

    @Test
    void probeReturnToDeckTopCandidatesShouldLoadArchiveCandidates() throws Exception {
        FakeCandidateProvider provider = new FakeCandidateProvider();
        provider.zoneRows = List.of(row(501L, "HBP99-005", "牌庫頂回收候選", "member", "Debut"));
        MatchCardSelectionProbeBuilder builder = builder(provider);

        SelectionProbe probe = builder.probeSelectionCandidates(1L, 2L, "RETURN_TO_DECK_TOP", node(
            "{\"rawText\":\"アーカイブから1枚デッキの上に戻す\"}"
        ));

        assertThat(probe.requestedCount()).isEqualTo(1);
        assertThat(provider.calls).containsExactly("loadCandidatesFromZone:ARCHIVE:false");
        assertThat(probe.candidates()).extracting(MatchEffectService.DecisionCandidate::zone).containsExactly("ARCHIVE");
    }

    @Test
    void probeReturnCandidatesShouldReturnNullWhenDiceConditionMisses() throws Exception {
        FakeCandidateProvider provider = new FakeCandidateProvider();
        MatchCardSelectionProbeBuilder builder = builder(provider, false);

        SelectionProbe probe = builder.probeSelectionCandidates(1L, 2L, "RETURN_TO_HAND", node(
            "{\"rawText\":\"サイコロを振り、条件未命中なら手札に戻す\"}"
        ));

        assertThat(probe).isNull();
        assertThat(provider.calls).isEmpty();
    }

    private MatchCardSelectionProbeBuilder builder(FakeCandidateProvider provider) {
        return builder(provider, true);
    }

    private MatchCardSelectionProbeBuilder builder(FakeCandidateProvider provider, boolean diceResult) {
        return new MatchCardSelectionProbeBuilder(
            effectTextParser,
            criteriaParser,
            requestResolver,
            provider,
            (rawText, effectNode, effectType) -> diceResult
        );
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private Map<String, Object> row(Long id, String cardId, String name, String cardType, String levelType) {
        return Map.of(
            "id", id,
            "card_id", cardId,
            "name", name,
            "card_type", cardType,
            "level_type", levelType
        );
    }

    private static final class FakeCandidateProvider implements MatchCardSelectionProbeBuilder.CandidateProvider {

        private final List<String> calls = new ArrayList<>();
        private List<Map<String, Object>> searchRows = List.of();
        private List<Map<String, Object>> topDeckRows = List.of();
        private List<Map<String, Object>> filteredRows = List.of();
        private List<Map<String, Object>> zoneRows = List.of();
        private List<Map<String, Object>> idRows = List.of();

        @Override
        public List<Map<String, Object>> loadSearchCandidates(Long matchId, Long userId, SearchCriteria criteria) {
            calls.add("loadSearchCandidates");
            return searchRows;
        }

        @Override
        public List<Map<String, Object>> loadTopDeckWindow(Long matchId, Long userId, int count) {
            calls.add("loadTopDeckWindow:" + count);
            return topDeckRows;
        }

        @Override
        public List<Map<String, Object>> loadCandidatesFromZone(
            Long matchId,
            Long userId,
            String zone,
            SearchCriteria criteria,
            boolean excludeLimitedSupport
        ) {
            calls.add("loadCandidatesFromZone:" + zone + ":" + excludeLimitedSupport);
            return zoneRows;
        }

        @Override
        public List<Map<String, Object>> loadCandidatesByCardInstanceIds(
            Long matchId,
            Long userId,
            List<Long> cardInstanceIds,
            SearchCriteria criteria
        ) {
            calls.add("loadCandidatesByCardInstanceIds:" + cardInstanceIds.size());
            return idRows;
        }

        @Override
        public List<Map<String, Object>> filterCandidatesByCriteria(List<Map<String, Object>> rows, SearchCriteria criteria) {
            calls.add("filterCandidatesByCriteria");
            return filteredRows;
        }
    }
}
