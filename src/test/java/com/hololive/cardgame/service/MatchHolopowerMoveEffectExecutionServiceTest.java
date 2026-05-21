package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchHolopowerMoveEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver =
        new MatchCardSelectionRequestResolver(effectTextParser);
    private final MatchHolopowerMoveEffectExecutionService service = new MatchHolopowerMoveEffectExecutionService(
        jdbcTemplate,
        effectTextParser,
        cardSelectionRequestResolver,
        (rawText, effectNode, effectType) -> true
    );

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveToHolopowerEffectShouldMoveDeckTopByDefault() throws Exception {
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq("DECK")))
            .thenReturn(101L);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("HOLOPOWER")))
            .thenReturn(3);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(3), eq(101L), eq(1L), eq(10L), eq("DECK")))
            .thenReturn(1);

        Map<String, Object> summary = service.executeMoveToHolopowerEffect(
            1L,
            10L,
            "MOVE_TO_HOLOPOWER",
            node("{\"value\":1,\"rawText\":\"自分のデッキの上から1枚をホロパワーにする。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "MOVE_TO_HOLOPOWER")
            .containsEntry("sourceZone", "DECK")
            .containsEntry("moveRequested", 1)
            .containsEntry("moveApplied", 1);
        assertThat(summary.get("movedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(101L);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveToHolopowerEffectShouldUseExplicitHandSourceZone() throws Exception {
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq("HAND")))
            .thenReturn(201L);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("HOLOPOWER")))
            .thenReturn(5);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(5), eq(201L), eq(1L), eq(10L), eq("HAND")))
            .thenReturn(1);

        Map<String, Object> summary = service.executeMoveToHolopowerEffect(
            1L,
            10L,
            "MOVE_TO_HOLOPOWER",
            node("{\"value\":1,\"sourceZone\":\"HAND\",\"rawText\":\"手札から1枚をホロパワーにする。\"}")
        );

        assertThat(summary)
            .containsEntry("sourceZone", "HAND")
            .containsEntry("moveApplied", 1);
        assertThat(summary.get("movedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(201L);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveToHolopowerEffectShouldUseExplicitArchiveSourceZone() throws Exception {
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(301L);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("HOLOPOWER")))
            .thenReturn(7);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(7), eq(301L), eq(1L), eq(10L), eq("ARCHIVE")))
            .thenReturn(1);

        Map<String, Object> summary = service.executeMoveToHolopowerEffect(
            1L,
            10L,
            "MOVE_TO_HOLOPOWER",
            node("{\"amount\":1,\"moveSourceZone\":\"ARCHIVE\",\"rawText\":\"アーカイブから1枚をホロパワーにする。\"}")
        );

        assertThat(summary)
            .containsEntry("sourceZone", "ARCHIVE")
            .containsEntry("moveApplied", 1);
        assertThat(summary.get("movedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(301L);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveToHolopowerEffectShouldLetExplicitDeckSourceOverrideRawTextHeuristic() throws Exception {
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq("DECK")))
            .thenReturn(401L);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("HOLOPOWER")))
            .thenReturn(9);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(9), eq(401L), eq(1L), eq(10L), eq("DECK")))
            .thenReturn(1);

        Map<String, Object> summary = service.executeMoveToHolopowerEffect(
            1L,
            10L,
            "MOVE_TO_HOLOPOWER",
            node(
                """
                {
                  "value": 1,
                  "holopowerSourceZone": "DECK",
                  "rawText": "自分のホロパワーを見る。その中から1枚を公開し、手札に加える。そして自分のデッキの上から1枚をホロパワーにする。"
                }
                """
            )
        );

        assertThat(summary)
            .containsEntry("sourceZone", "DECK")
            .containsEntry("moveApplied", 1);
        assertThat(summary.get("movedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(401L);
    }

    @Test
    void executeMoveToHolopowerEffectShouldReturnNoOpWhenDiceConditionMisses() throws Exception {
        MatchHolopowerMoveEffectExecutionService blockedService = new MatchHolopowerMoveEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            cardSelectionRequestResolver,
            (rawText, effectNode, effectType) -> false
        );

        Map<String, Object> summary = blockedService.executeMoveToHolopowerEffect(
            1L,
            10L,
            "MOVE_TO_HOLOPOWER",
            node("{\"rawText\":\"サイコロを振る。自分のデッキの上から1枚をホロパワーにする。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "MOVE_TO_HOLOPOWER")
            .containsEntry("applied", false)
            .containsEntry("reason", "骰子條件未命中")
            .containsEntry("rawText", "サイコロを振る。自分のデッキの上から1枚をホロパワーにする。");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveToHolopowerEffectShouldReturnZeroAppliedWhenSourceIsEmpty() throws Exception {
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq("DECK")))
            .thenReturn(null);

        Map<String, Object> summary = service.executeMoveToHolopowerEffect(
            1L,
            10L,
            "MOVE_TO_HOLOPOWER",
            node("{\"value\":1,\"rawText\":\"自分のデッキの上から1枚をホロパワーにする。\"}")
        );

        assertThat(summary)
            .containsEntry("sourceZone", "DECK")
            .containsEntry("moveRequested", 1)
            .containsEntry("moveApplied", 0);
        assertThat(summary.get("movedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .isEmpty();
        verify(jdbcTemplate).query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq("DECK"));
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
