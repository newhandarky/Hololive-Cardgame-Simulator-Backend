package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MatchRevealToArchiveEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final SearchCriteriaParser searchCriteriaParser = new SearchCriteriaParser(jdbcTemplate, effectTextParser);
    private final MatchCardSelectionRequestResolver requestResolver = new MatchCardSelectionRequestResolver(effectTextParser);
    private final MatchEffectSearchService searchService = new MatchEffectSearchService(jdbcTemplate, effectTextParser);
    private final MatchRevealToArchiveEffectExecutionService service = new MatchRevealToArchiveEffectExecutionService(
        jdbcTemplate,
        searchCriteriaParser,
        requestResolver,
        searchService
    );

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeRevealToArchiveEffectShouldArchiveDeckCandidatesWhenCriteriaIsEmpty() throws Exception {
        when(jdbcTemplate.query(
            contains("JOIN cards"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq("DECK"),
            eq(""),
            eq(""),
            eq(""),
            eq(""),
            eq(""),
            eq(""),
            eq(false),
            eq(""),
            eq("")
        )).thenReturn(List.of(candidate(101L, "CARD_A"), candidate(102L, "CARD_B")));
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(4);
        when(jdbcTemplate.update(contains("AND zone = 'DECK'"), eq(4), eq(101L), eq(1L), eq(10L))).thenReturn(1);
        when(jdbcTemplate.update(contains("AND zone = 'DECK'"), eq(5), eq(102L), eq(1L), eq(10L))).thenReturn(1);

        Map<String, Object> summary = service.executeRevealToArchiveEffect(
            1L,
            10L,
            "REVEAL_TO_ARCHIVE",
            node("{\"value\":2,\"rawText\":\"自分のデッキから、カード2枚を公開し、アーカイブする。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "REVEAL_TO_ARCHIVE")
            .containsEntry("archiveRequested", 2)
            .containsEntry("candidateCount", 2)
            .containsEntry("archiveApplied", 2);
        assertThat(summary.get("archivedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(101L, 102L);
        assertThat(summary.get("archivedCardIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly("CARD_A", "CARD_B");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeRevealToArchiveEffectShouldFilterDeckCandidatesByCriteria() throws Exception {
        when(jdbcTemplate.query(
            contains("JOIN cards"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq("DECK"),
            eq("MEMBER"),
            eq("MEMBER"),
            eq("DEBUT"),
            eq("DEBUT"),
            eq(""),
            eq(""),
            eq(false),
            eq("#FLOW"),
            eq("#FLOW")
        )).thenReturn(List.of(candidate(201L, "FLOW_DEBUT")));
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(7);
        when(jdbcTemplate.update(contains("AND zone = 'DECK'"), eq(7), eq(201L), eq(1L), eq(10L))).thenReturn(1);

        Map<String, Object> summary = service.executeRevealToArchiveEffect(
            1L,
            10L,
            "REVEAL_TO_ARCHIVE",
            node(
                "{\"rawText\":\"自分のデッキから、#FLOWを持つDebutホロメン1枚を公開し、アーカイブする。\"}"
            )
        );

        assertThat(summary)
            .containsEntry("archiveRequested", 1)
            .containsEntry("candidateCount", 1)
            .containsEntry("archiveApplied", 1)
            .containsKey("criteria");
        @SuppressWarnings("unchecked")
        Map<String, Object> criteria = (Map<String, Object>) summary.get("criteria");
        assertThat(criteria)
            .containsEntry("cardType", "MEMBER")
            .containsEntry("levelType", "DEBUT")
            .containsEntry("tag", "#FLOW");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeRevealToArchiveEffectShouldApplyOnlyAvailableCandidates() throws Exception {
        when(jdbcTemplate.query(
            contains("JOIN cards"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq("DECK"),
            eq(""),
            eq(""),
            eq(""),
            eq(""),
            eq(""),
            eq(""),
            eq(false),
            eq(""),
            eq("")
        )).thenReturn(List.of(candidate(301L, "ONLY_CARD")));
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(1);
        when(jdbcTemplate.update(contains("AND zone = 'DECK'"), eq(1), eq(301L), eq(1L), eq(10L))).thenReturn(1);

        Map<String, Object> summary = service.executeRevealToArchiveEffect(
            1L,
            10L,
            "REVEAL_TO_ARCHIVE",
            node("{\"amount\":3,\"rawText\":\"自分のデッキから、カード3枚を公開し、アーカイブする。\"}")
        );

        assertThat(summary)
            .containsEntry("archiveRequested", 3)
            .containsEntry("candidateCount", 1)
            .containsEntry("archiveApplied", 1);
        verify(jdbcTemplate).update(contains("is_face_down = FALSE"), eq(1), eq(301L), eq(1L), eq(10L));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeRevealToArchiveEffectShouldReturnZeroAppliedWhenNoCandidateMatches() throws Exception {
        when(jdbcTemplate.query(
            contains("JOIN cards"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq("DECK"),
            eq("MEMBER"),
            eq("MEMBER"),
            eq(""),
            eq(""),
            eq(""),
            eq(""),
            eq(false),
            eq("#FLOW"),
            eq("#FLOW")
        )).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(1);

        Map<String, Object> summary = service.executeRevealToArchiveEffect(
            1L,
            10L,
            "REVEAL_TO_ARCHIVE",
            node("{\"rawText\":\"自分のデッキから、#FLOWを持つホロメン1枚を公開し、アーカイブする。\"}")
        );

        assertThat(summary)
            .containsEntry("archiveRequested", 1)
            .containsEntry("candidateCount", 0)
            .containsEntry("archiveApplied", 0);
        assertThat(summary.get("archivedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .isEmpty();
    }

    private Map<String, Object> candidate(Long id, String cardId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("card_id", cardId);
        row.put("card_type", "MEMBER");
        row.put("level_type", "DEBUT");
        row.put("name", "測試成員");
        row.put("tags_json", "[\"#FLOW\"]");
        row.put("main_color", null);
        row.put("sub_color", null);
        row.put("cheer_color", null);
        row.put("is_rested", null);
        row.put("remain_hp", null);
        return row;
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
