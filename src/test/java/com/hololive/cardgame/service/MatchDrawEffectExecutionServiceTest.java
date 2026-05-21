package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.DrawAction;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.EffectResolver;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchDrawEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectResolver effectResolver = mock(EffectResolver.class);
    private final GameActionExecutor gameActionExecutor = mock(GameActionExecutor.class);
    private final MatchDrawEffectExecutionService service = new MatchDrawEffectExecutionService(
        jdbcTemplate,
        objectMapper,
        effectResolver,
        gameActionExecutor,
        new EffectTextParser(objectMapper),
        (rawText, effectNode, effectType) -> true
    );

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeDrawEffectShouldUseActionPipelineWhenItReturnsMovedCards() throws Exception {
        DrawAction action = new DrawAction(10L, 2, "DECK", "HAND");
        when(jdbcTemplate.query(contains("SELECT turn_number"), any(ResultSetExtractor.class), eq(1L)))
            .thenReturn(3);
        when(effectResolver.resolve(any(EffectContext.class), eq("DRAW"), any(JsonNode.class)))
            .thenReturn(List.of(action));
        when(gameActionExecutor.execute(any(EffectContext.class), eq(List.of(action))))
            .thenReturn(List.of(ActionResult.success("DRAW", Map.of("cardInstanceIds", List.of(101L, "102", "bad")))));

        Map<String, Object> summary = service.executeDrawEffect(
            1L,
            10L,
            "DRAW",
            node("{\"value\":2,\"rawText\":\"自分のデッキを2枚引く。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "DRAW")
            .containsEntry("drawRequested", 2)
            .containsEntry("drawApplied", 2);
        assertThat(summary.get("drawnCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(101L, 102L);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeDrawEffectShouldFallbackToSqlWhenPipelineReturnsNoMovedCards() throws Exception {
        when(jdbcTemplate.query(contains("SELECT turn_number"), any(ResultSetExtractor.class), eq(1L)))
            .thenReturn(2);
        when(effectResolver.resolve(any(EffectContext.class), eq("DRAW"), any(JsonNode.class)))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(1L), eq(10L), eq("HAND")))
            .thenReturn(4);
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(201L, 202L);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), any(), any(), any(), any()))
            .thenReturn(1);

        Map<String, Object> summary = service.executeDrawEffect(
            1L,
            10L,
            "DRAW",
            node("{\"rawText\":\"自分のデッキを2枚引く。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "DRAW")
            .containsEntry("drawRequested", 2)
            .containsEntry("drawApplied", 2);
        assertThat(summary.get("drawnCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(201L, 202L);
        verify(jdbcTemplate).update(contains("UPDATE match_cards"), eq(4), eq(201L), eq(1L), eq(10L));
        verify(jdbcTemplate).update(contains("UPDATE match_cards"), eq(5), eq(202L), eq(1L), eq(10L));
    }

    @Test
    void executeDrawEffectShouldReturnNoOpWhenDiceConditionMisses() throws Exception {
        MatchDrawEffectExecutionService blockedService = new MatchDrawEffectExecutionService(
            jdbcTemplate,
            objectMapper,
            effectResolver,
            gameActionExecutor,
            new EffectTextParser(objectMapper),
            (rawText, effectNode, effectType) -> false
        );

        Map<String, Object> summary = blockedService.executeDrawEffect(
            1L,
            10L,
            "DRAW",
            node("{\"rawText\":\"サイコロを振る。自分のデッキを1枚引く。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "DRAW")
            .containsEntry("applied", false)
            .containsEntry("reason", "骰子條件未命中")
            .containsEntry("rawText", "サイコロを振る。自分のデッキを1枚引く。");
        verifyNoInteractions(jdbcTemplate, effectResolver, gameActionExecutor);
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
