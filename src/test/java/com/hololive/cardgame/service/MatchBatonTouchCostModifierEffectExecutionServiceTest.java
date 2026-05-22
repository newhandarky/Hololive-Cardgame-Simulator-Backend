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

class MatchBatonTouchCostModifierEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void executeBatonTouchCostModifierEffectShouldReturnNoOpWhenModifierCannotBeParsed() throws Exception {
        MatchBatonTouchCostModifierEffectExecutionService service = service(null, 20L, 3, 10L, 1000L);

        Map<String, Object> summary = service.executeBatonTouchCostModifierEffect(
            1L,
            10L,
            "BATON_TOUCH_COST_MODIFIER",
            node("{\"rawText\":\"このターンの間、何もしない。\"}"),
            "SELF",
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "BATON_TOUCH_COST_MODIFIER")
            .containsEntry("applied", false)
            .containsEntry("reason", "找不到有效的バトンタッチ無色修正值");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void executeBatonTouchCostModifierEffectShouldInsertModifierForResolvedTarget() throws Exception {
        MatchBatonTouchCostModifierEffectExecutionService service = service(100L, 20L, 3, 10L, 1000L);
        when(jdbcTemplate.update(
            contains("BATON_TOUCH_COLORLESS_MODIFIER"),
            eq(1L),
            eq(10L),
            eq(10L),
            eq("DEBUFF"),
            eq(1),
            eq(4),
            contains("\"targetHolomemId\":100")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeBatonTouchCostModifierEffect(
            1L,
            10L,
            "BATON_TOUCH_COST_MODIFIER",
            node("{\"rawText\":\"このターンの間、このホロメンのバトンタッチに必要な無色+1。\"}"),
            "SELF",
            1000L
        );

        assertThat(summary)
            .containsEntry("effectType", "BATON_TOUCH_COST_MODIFIER")
            .containsEntry("applied", true)
            .containsEntry("targetHolomemId", 100L)
            .containsEntry("targetHolomemCardInstanceId", 1000L)
            .containsEntry("modifierValue", 1)
            .containsEntry("expiresTurn", 4);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeBatonTouchCostModifierEffectShouldFallbackToOwnCenterWhenTargetCannotBeResolved() throws Exception {
        MatchBatonTouchCostModifierEffectExecutionService service = service(null, 20L, 3, 10L, 1000L);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(100L);
        when(jdbcTemplate.update(
            contains("BATON_TOUCH_COLORLESS_MODIFIER"),
            eq(1L),
            eq(10L),
            eq(10L),
            eq("DEBUFF"),
            eq(2),
            eq(4),
            contains("\"targetHolomemId\":100")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeBatonTouchCostModifierEffect(
            1L,
            10L,
            "BATON_TOUCH_COST_MODIFIER",
            node("{\"rawText\":\"センターホロメンのバトンタッチに必要な無色+2。\"}"),
            "SELF",
            null
        );

        assertThat(summary)
            .containsEntry("applied", true)
            .containsEntry("targetHolomemId", 100L)
            .containsEntry("modifierValue", 2);
        verify(jdbcTemplate).query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeBatonTouchCostModifierEffectShouldFallbackToOpponentCenterWhenTargetTypeIsOpponent() throws Exception {
        MatchBatonTouchCostModifierEffectExecutionService service = service(null, 20L, 3, 20L, 1000L);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(20L)))
            .thenReturn(200L);
        when(jdbcTemplate.update(
            contains("BATON_TOUCH_COLORLESS_MODIFIER"),
            eq(1L),
            eq(10L),
            eq(20L),
            eq("DEBUFF"),
            eq(1),
            eq(4),
            contains("\"targetHolomemId\":200")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeBatonTouchCostModifierEffect(
            1L,
            10L,
            "BATON_TOUCH_COST_MODIFIER",
            node("{\"rawText\":\"相手のセンターホロメンのバトンタッチに必要な無色+1。\"}"),
            "OPPONENT",
            null
        );

        assertThat(summary)
            .containsEntry("applied", true)
            .containsEntry("targetHolomemId", 200L)
            .containsEntry("targetHolomemCardInstanceId", 2000L)
            .containsEntry("modifierValue", 1);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeBatonTouchCostModifierEffectShouldReturnNoOpWhenFallbackTargetIsMissing() throws Exception {
        MatchBatonTouchCostModifierEffectExecutionService service = service(null, 20L, 3, null, 1000L);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(null);

        Map<String, Object> summary = service.executeBatonTouchCostModifierEffect(
            1L,
            10L,
            "BATON_TOUCH_COST_MODIFIER",
            node("{\"value\":1}"),
            "SELF",
            null
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "找不到可套用バトンタッチ修正的 CENTER 目標");
    }

    private MatchBatonTouchCostModifierEffectExecutionService service(
        Long resolvedTargetHolomemId,
        Long opponentUserId,
        int currentTurn,
        Long targetOwnerUserId,
        Long targetCardInstanceId
    ) {
        return new MatchBatonTouchCostModifierEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            (matchId, userId, targetType, targetHolomemCardInstanceId, allowOpponent) -> resolvedTargetHolomemId,
            (matchId, userId) -> opponentUserId,
            matchId -> currentTurn,
            (matchId, holomemId) -> targetOwnerUserId,
            matchHolomemId -> matchHolomemId * 10
        );
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
