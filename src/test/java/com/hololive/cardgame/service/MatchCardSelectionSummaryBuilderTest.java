package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.service.effect.SearchCriteria;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchCardSelectionSummaryBuilderTest {

    private final MatchCardSelectionSummaryBuilder builder = new MatchCardSelectionSummaryBuilder();

    @Test
    void buildDeckBottomReorderCandidateShouldUseFrontendPayloadKeys() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("card_id", "HBP99-001");
        row.put("name", "排序候選");
        row.put("card_type", "member");
        row.put("level_type", "1st");

        Map<String, Object> candidate = builder.buildDeckBottomReorderCandidate(row, 101L);

        assertThat(candidate)
            .containsEntry("cardInstanceId", 101L)
            .containsEntry("cardId", "HBP99-001")
            .containsEntry("name", "排序候選")
            .containsEntry("cardType", "MEMBER")
            .containsEntry("levelType", "FIRST")
            .containsEntry("zone", "DECK");
    }

    @Test
    void buildSearchEffectSummaryShouldPreserveSearchPayloadShape() {
        SearchCriteria criteria = new SearchCriteria(
            "MEMBER",
            "DEBUT",
            "#TEST",
            "Name",
            "RED",
            true,
            10,
            90,
            List.of(new SearchCriteria("MEMBER", "", "", "")),
            List.of(new SearchCriteria("", "DEBUT", "", ""))
        );
        Map<String, Object> reorderCandidate = Map.of("cardInstanceId", 201L, "cardId", "HBP99-002");

        Map<String, Object> summary = builder.buildSearchEffectSummary(
            "SEARCH",
            1,
            List.of(Map.of("id", 101L)),
            List.of(Map.of("id", 101L), Map.of("id", 102L)),
            4,
            "DECK",
            true,
            List.of(301L),
            List.of("HBP99-003"),
            List.of(101L),
            List.of(101L),
            List.of("HBP99-001"),
            List.of(reorderCandidate),
            criteria
        );

        assertThat(summary)
            .containsEntry("effectType", "SEARCH")
            .containsEntry("applied", true)
            .containsEntry("searchRequested", 1)
            .containsEntry("candidateCount", 1)
            .containsEntry("searchPoolCount", 2)
            .containsEntry("lookTopCount", 4)
            .containsEntry("searchSourceZone", "DECK")
            .containsEntry("searchApplied", 1)
            .containsEntry("archiveUnselectedTopWindow", true)
            .containsEntry("archiveRemainderApplied", 1)
            .containsEntry("archiveRemainderCardInstanceIds", List.of(301L))
            .containsEntry("archiveRemainderCardIds", List.of("HBP99-003"))
            .containsEntry("selectedByClient", true)
            .containsEntry("searchedCardInstanceIds", List.of(101L))
            .containsEntry("searchedCardIds", List.of("HBP99-001"))
            .containsEntry("requiresDeckBottomReorder", true)
            .containsEntry("deckBottomReorderCandidateCardInstanceIds", List.of(201L))
            .containsEntry("deckBottomReorderCandidates", List.of(reorderCandidate));

        assertThat(summary.get("criteria"))
            .isInstanceOf(Map.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("cardType", "MEMBER")
            .containsEntry("levelType", "DEBUT")
            .containsEntry("tag", "#TEST")
            .containsEntry("nameContains", "Name")
            .containsEntry("color", "RED")
            .containsEntry("rested", true)
            .containsEntry("minRemainHp", 10)
            .containsEntry("maxRemainHp", 90);
    }

    @Test
    void buildReturnSummariesShouldPreserveSelectionAndCriteriaFields() {
        SearchCriteria criteria = new SearchCriteria("SUPPORT", "", "#TAG", "tool");

        Map<String, Object> handSummary = builder.buildReturnToHandSummary(
            "RETURN_TO_HAND",
            2,
            List.of(Map.of("id", 1L), Map.of("id", 2L), Map.of("id", 3L)),
            List.of(1L, 2L),
            List.of("HBP99-004", "HBP99-005"),
            List.of(1L, 2L),
            criteria,
            true,
            "ARCHIVE"
        );
        Map<String, Object> topSummary = builder.buildReturnToDeckTopSummary(
            "RETURN_TO_DECK_TOP",
            1,
            List.of(Map.of("id", 4L)),
            List.of(4L),
            List.of("HBP99-006"),
            null,
            criteria
        );

        assertThat(handSummary)
            .containsEntry("effectType", "RETURN_TO_HAND")
            .containsEntry("returnRequested", 2)
            .containsEntry("candidateCount", 3)
            .containsEntry("returnApplied", 2)
            .containsEntry("selectedByClient", true)
            .containsEntry("returnedCardInstanceIds", List.of(1L, 2L))
            .containsEntry("returnedCardIds", List.of("HBP99-004", "HBP99-005"));
        assertThat(handSummary.get("criteria"))
            .isInstanceOf(Map.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("excludeLimitedSupport", true)
            .containsEntry("sourceZone", "ARCHIVE");

        assertThat(topSummary)
            .containsEntry("effectType", "RETURN_TO_DECK_TOP")
            .containsEntry("returnRequested", 1)
            .containsEntry("candidateCount", 1)
            .containsEntry("returnApplied", 1)
            .containsEntry("selectedByClient", false)
            .containsEntry("returnedCardInstanceIds", List.of(4L))
            .containsEntry("returnedCardIds", List.of("HBP99-006"));
    }
}
