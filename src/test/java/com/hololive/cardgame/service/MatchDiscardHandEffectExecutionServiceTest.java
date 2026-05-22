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

class MatchDiscardHandEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final SearchCriteriaParser searchCriteriaParser = new SearchCriteriaParser(jdbcTemplate, effectTextParser);
    private final MatchCardSelectionRequestResolver requestResolver = new MatchCardSelectionRequestResolver(effectTextParser);
    private final MatchEffectSearchService searchService = new MatchEffectSearchService(jdbcTemplate, effectTextParser);
    private final MatchDiscardHandEffectExecutionService service = new MatchDiscardHandEffectExecutionService(
        jdbcTemplate,
        objectMapper,
        effectTextParser,
        searchCriteriaParser,
        requestResolver,
        searchService
    );

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeDiscardHandEffectShouldDiscardFirstHandCardsWhenCriteriaIsEmpty() throws Exception {
        when(jdbcTemplate.query(
            contains("zone = 'HAND'"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq(2)
        )).thenReturn(List.of(row(101L, "CARD_A"), row(102L, "CARD_B")));
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(5);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(5), eq(101L), eq(1L), eq(10L))).thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(6), eq(102L), eq(1L), eq(10L))).thenReturn(1);

        Map<String, Object> summary = service.executeDiscardHandEffect(
            1L,
            10L,
            "DISCARD_HAND",
            node("{\"value\":2,\"rawText\":\"自分の手札2枚をアーカイブする。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "DISCARD_HAND")
            .containsEntry("discardRequested", 2)
            .containsEntry("discardApplied", 2);
        assertThat(summary.get("discardedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(101L, 102L);
        assertThat(summary.get("discardedCardIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly("CARD_A", "CARD_B");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeDiscardHandEffectShouldFilterHandByCostClauseCriteria() throws Exception {
        when(jdbcTemplate.query(
            contains("JOIN cards"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq("HAND"),
            eq("MEMBER"),
            eq("MEMBER"),
            eq(""),
            eq(""),
            eq(""),
            eq(""),
            eq(false),
            eq("#FLOW"),
            eq("#FLOW")
        )).thenReturn(List.of(candidate(201L, "FLOW_MEMBER", "MEMBER", "#FLOW")));
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(8);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(8), eq(201L), eq(1L), eq(10L))).thenReturn(1);

        Map<String, Object> summary = service.executeDiscardHandEffect(
            1L,
            10L,
            "DISCARD_HAND",
            node(
                "{\"rawText\":\"自分の手札の#FLOW GLOWを持つホロメン1枚をアーカイブできる：自分のデッキを1枚引く。\"}"
            )
        );

        assertThat(summary)
            .containsEntry("discardRequested", 1)
            .containsEntry("discardApplied", 1)
            .containsKey("discardCriteria");
        assertThat(summary.get("discardedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(201L);

        @SuppressWarnings("unchecked")
        Map<String, Object> criteria = (Map<String, Object>) summary.get("discardCriteria");
        assertThat(criteria)
            .containsEntry("cardType", "MEMBER")
            .containsEntry("tag", "#FLOW");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeDiscardHandEffectShouldApplyOnlyAvailableHandCards() throws Exception {
        when(jdbcTemplate.query(
            contains("zone = 'HAND'"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq(3)
        )).thenReturn(List.of(row(301L, "ONLY_CARD")));
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(1), eq(301L), eq(1L), eq(10L))).thenReturn(1);

        Map<String, Object> summary = service.executeDiscardHandEffect(
            1L,
            10L,
            "DISCARD_HAND",
            node("{\"amount\":3,\"rawText\":\"自分の手札3枚をアーカイブする。\"}")
        );

        assertThat(summary)
            .containsEntry("discardRequested", 3)
            .containsEntry("discardApplied", 1);
        verify(jdbcTemplate).update(contains("AND zone = 'HAND'"), eq(1), eq(301L), eq(1L), eq(10L));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeDiscardHandEffectShouldReturnZeroAppliedWhenNoCriteriaCandidateMatches() throws Exception {
        when(jdbcTemplate.query(
            contains("JOIN cards"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq("HAND"),
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

        Map<String, Object> summary = service.executeDiscardHandEffect(
            1L,
            10L,
            "DISCARD_HAND",
            node(
                "{\"rawText\":\"自分の手札の#FLOW GLOWを持つホロメン1枚をアーカイブできる：自分のデッキを1枚引く。\"}"
            )
        );

        assertThat(summary)
            .containsEntry("discardRequested", 1)
            .containsEntry("discardApplied", 0)
            .containsKey("discardCriteria");
        assertThat(summary.get("discardedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .isEmpty();
    }

    private Map<String, Object> row(Long id, String cardId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("card_id", cardId);
        return row;
    }

    private Map<String, Object> candidate(Long id, String cardId, String cardType, String tag) {
        Map<String, Object> row = row(id, cardId);
        row.put("card_type", cardType);
        row.put("level_type", null);
        row.put("name", "測試成員");
        row.put("tags_json", "[\"" + tag + "\"]");
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
