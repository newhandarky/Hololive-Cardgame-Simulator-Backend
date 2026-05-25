package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchSummonToStageEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final SearchCriteriaParser searchCriteriaParser = new SearchCriteriaParser(jdbcTemplate, effectTextParser);
    private final MatchCardSelectionRequestResolver requestResolver = new MatchCardSelectionRequestResolver(effectTextParser);
    private final MatchEffectSearchService searchService = new MatchEffectSearchService(jdbcTemplate, effectTextParser);
    private final MatchSummonToStageEffectExecutionService service = new MatchSummonToStageEffectExecutionService(
        jdbcTemplate,
        effectTextParser,
        searchCriteriaParser,
        requestResolver,
        searchService
    );

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSummonToStageEffectShouldSummonDeckMemberToBackByDefault() throws Exception {
        whenDeckCandidates(List.of(candidate(101L, "CARD_A", "DEBUT", "#FLOW")));
        whenCurrentTurn(3);
        whenZoneCount("CENTER", 1);
        whenZoneCount("BACK", 2);
        when(jdbcTemplate.update(contains("AND zone = 'DECK'"), eq(101L), eq(1L), eq(10L))).thenReturn(1);
        when(jdbcTemplate.query(
            contains("INSERT INTO match_holomems"),
            any(ResultSetExtractor.class),
            eq(1L),
            eq(10L),
            eq(101L),
            eq("CARD_A"),
            eq("BACK"),
            eq("DEBUT"),
            eq(3)
        )).thenReturn(501L);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(stack_order)"), eq(Integer.class), eq(501L))).thenReturn(1);

        Map<String, Object> summary = service.executeSummonToStageEffect(
            1L,
            10L,
            "SUMMON_TO_STAGE",
            node("{\"rawText\":\"自分のデッキから、ホロメン1枚を公開し、ステージに出す。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "SUMMON_TO_STAGE")
            .containsEntry("summonRequested", 1)
            .containsEntry("candidateCount", 1)
            .containsEntry("summonApplied", 1);
        assertThat(summary.get("summonedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(101L);
        assertThat(summary.get("summonedHolomemIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(501L);
        assertThat(summary.get("summonedZones"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly("BACK");
        verify(jdbcTemplate).update(
            contains("INSERT INTO match_holomem_stack_cards"),
            eq(501L),
            eq(101L),
            eq(1)
        );
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSummonToStageEffectShouldFilterMemberCriteriaAndUsePreferredCenterWhenAvailable() throws Exception {
        when(jdbcTemplate.query(
            contains("JOIN cards"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq("DECK"),
            eq("MEMBER"),
            eq("MEMBER"),
            eq("FIRST"),
            eq("FIRST"),
            eq(""),
            eq(""),
            eq(false),
            eq("#Justice"),
            eq("#Justice")
        )).thenReturn(List.of(candidate(201L, "JUSTICE_FIRST", "FIRST", "#Justice")));
        whenCurrentTurn(5);
        whenZoneCount("CENTER", 0);
        whenZoneCount("BACK", 4);
        when(jdbcTemplate.update(contains("AND zone = 'DECK'"), eq(201L), eq(1L), eq(10L))).thenReturn(1);
        when(jdbcTemplate.query(
            contains("INSERT INTO match_holomems"),
            any(ResultSetExtractor.class),
            eq(1L),
            eq(10L),
            eq(201L),
            eq("JUSTICE_FIRST"),
            eq("CENTER"),
            eq("FIRST"),
            eq(5)
        )).thenReturn(601L);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(stack_order)"), eq(Integer.class), eq(601L))).thenReturn(1);

        Map<String, Object> summary = service.executeSummonToStageEffect(
            1L,
            10L,
            "SUMMON_TO_STAGE",
            node(
                "{\"toZone\":\"CENTER\",\"rawText\":\"自分のデッキから、#Justiceを持つ1stホロメン1枚を公開し、センターに出す。\"}"
            )
        );

        assertThat(summary)
            .containsEntry("candidateCount", 1)
            .containsEntry("summonApplied", 1);
        assertThat(summary.get("summonedZones"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly("CENTER");
        @SuppressWarnings("unchecked")
        Map<String, Object> criteria = (Map<String, Object>) summary.get("criteria");
        assertThat(criteria)
            .containsEntry("cardType", "MEMBER")
            .containsEntry("levelType", "FIRST")
            .containsEntry("tag", "#Justice");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSummonToStageEffectShouldReturnZeroAppliedWhenStageIsFull() throws Exception {
        whenDeckCandidates(List.of(candidate(301L, "CARD_FULL", "DEBUT", "#FLOW")));
        whenCurrentTurn(2);
        whenZoneCount("CENTER", 1);
        whenZoneCount("BACK", 5);

        Map<String, Object> summary = service.executeSummonToStageEffect(
            1L,
            10L,
            "SUMMON_TO_STAGE",
            node("{\"rawText\":\"自分のデッキから、ホロメン1枚を公開し、ステージに出す。\"}")
        );

        assertThat(summary)
            .containsEntry("summonRequested", 1)
            .containsEntry("candidateCount", 1)
            .containsEntry("summonApplied", 0);
        assertThat(summary.get("summonedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .isEmpty();
        verify(jdbcTemplate, never()).update(contains("AND zone = 'DECK'"), eq(301L), eq(1L), eq(10L));
    }

    private void whenDeckCandidates(List<Map<String, Object>> candidates) {
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
            eq(""),
            eq("")
        )).thenReturn(candidates);
    }

    private void whenCurrentTurn(int turnNumber) {
        when(jdbcTemplate.query(
            contains("SELECT turn_number"),
            any(ResultSetExtractor.class),
            eq(1L)
        )).thenReturn(turnNumber);
    }

    private void whenZoneCount(String zone, int count) {
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), eq(1L), eq(10L), eq(zone)))
            .thenReturn(count);
    }

    private Map<String, Object> candidate(Long id, String cardId, String levelType, String tag) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("card_id", cardId);
        row.put("card_type", "MEMBER");
        row.put("level_type", levelType);
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
