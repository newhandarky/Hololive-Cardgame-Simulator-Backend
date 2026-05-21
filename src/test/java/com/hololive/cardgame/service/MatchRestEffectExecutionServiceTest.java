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

class MatchRestEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void executeRestEffectShouldReturnNoOpWhenDiceConditionMisses() throws Exception {
        MatchRestEffectExecutionService service = service(
            false,
            (matchId, userId, targetType, targetHolomemCardInstanceId, defaultOpponent) -> 100L,
            (matchId, userId) -> 20L,
            matchHolomemId -> 1000L
        );

        Map<String, Object> summary = service.executeRestEffect(
            1L,
            10L,
            "REST",
            node("{\"rawText\":\"サイコロを振る。相手のホロメンをお休みにする。\"}"),
            "ENEMY",
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "REST")
            .containsEntry("applied", false)
            .containsEntry("reason", "骰子條件未命中")
            .containsEntry("rawText", "サイコロを振る。相手のホロメンをお休みにする。");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void executeRestEffectShouldRestResolvedTargetHolomem() throws Exception {
        MatchRestEffectExecutionService service = service(
            true,
            (matchId, userId, targetType, targetHolomemCardInstanceId, defaultOpponent) -> 100L,
            (matchId, userId) -> 20L,
            matchHolomemId -> 1000L
        );
        when(jdbcTemplate.update(contains("UPDATE match_holomems"), eq(100L), eq(1L)))
            .thenReturn(1);

        Map<String, Object> summary = service.executeRestEffect(
            1L,
            10L,
            "REST",
            node("{\"rawText\":\"相手のホロメンをお休みにする。\"}"),
            "ENEMY",
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "REST")
            .containsEntry("applied", true)
            .containsEntry("targetHolomemId", 100L)
            .containsEntry("targetHolomemCardInstanceId", 1000L)
            .containsEntry("rested", true);
        verify(jdbcTemplate).update(contains("UPDATE match_holomems"), eq(100L), eq(1L));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeRestEffectShouldFallbackToFirstBackHolomemWhenRawTextTargetsBack() throws Exception {
        MatchRestEffectExecutionService service = service(
            true,
            (matchId, userId, targetType, targetHolomemCardInstanceId, defaultOpponent) -> null,
            (matchId, userId) -> 20L,
            matchHolomemId -> 2000L
        );
        when(jdbcTemplate.query(contains("FROM match_holomems"), any(ResultSetExtractor.class), eq(1L), eq(20L)))
            .thenReturn(200L);
        when(jdbcTemplate.update(contains("UPDATE match_holomems"), eq(200L), eq(1L)))
            .thenReturn(1);

        Map<String, Object> summary = service.executeRestEffect(
            1L,
            10L,
            "REST",
            node("{\"rawText\":\"相手のバックホロメン1人をお休みにする。\"}"),
            "ENEMY",
            null
        );

        assertThat(summary)
            .containsEntry("applied", true)
            .containsEntry("targetHolomemId", 200L)
            .containsEntry("targetHolomemCardInstanceId", 2000L);
        verify(jdbcTemplate).query(contains("FROM match_holomems"), any(ResultSetExtractor.class), eq(1L), eq(20L));
    }

    @Test
    void executeRestEffectShouldReturnNoOpWhenNoTargetExists() throws Exception {
        MatchRestEffectExecutionService service = service(
            true,
            (matchId, userId, targetType, targetHolomemCardInstanceId, defaultOpponent) -> null,
            (matchId, userId) -> 20L,
            matchHolomemId -> 2000L
        );

        Map<String, Object> summary = service.executeRestEffect(
            1L,
            10L,
            "REST",
            node("{\"rawText\":\"相手のホロメンをお休みにする。\"}"),
            "ENEMY",
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "REST")
            .containsEntry("applied", false)
            .containsEntry("reason", "找不到可設為休息的 Holomem");
    }

    @Test
    void executeRestEffectShouldReturnNoOpWhenUpdateFails() throws Exception {
        MatchRestEffectExecutionService service = service(
            true,
            (matchId, userId, targetType, targetHolomemCardInstanceId, defaultOpponent) -> 100L,
            (matchId, userId) -> 20L,
            matchHolomemId -> 1000L
        );
        when(jdbcTemplate.update(contains("UPDATE match_holomems"), eq(100L), eq(1L)))
            .thenReturn(0);

        Map<String, Object> summary = service.executeRestEffect(
            1L,
            10L,
            "REST",
            node("{\"rawText\":\"相手のホロメンをお休みにする。\"}"),
            "ENEMY",
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "REST")
            .containsEntry("applied", false)
            .containsEntry("reason", "設定休息狀態失敗");
    }

    private MatchRestEffectExecutionService service(
        boolean diceApplies,
        MatchRestEffectExecutionService.TargetHolomemResolver targetHolomemResolver,
        MatchRestEffectExecutionService.OpponentUserResolver opponentUserResolver,
        MatchRestEffectExecutionService.HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        return new MatchRestEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            (rawText, effectNode, effectType) -> diceApplies,
            targetHolomemResolver,
            opponentUserResolver,
            holomemCardInstanceResolver
        );
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
