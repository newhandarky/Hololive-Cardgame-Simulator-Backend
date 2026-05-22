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

class MatchSwapCenterBackEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void executeSwapCenterBackEffectShouldReturnNoOpWhenDiceConditionMisses() throws Exception {
        MatchSwapCenterBackEffectExecutionService service = service(false, 20L, false);

        Map<String, Object> summary = service.executeSwapCenterBackEffect(
            1L,
            10L,
            "SWAP_CENTER_BACK",
            node("{\"rawText\":\"サイコロを振る。センターホロメンとバックホロメンを交代する。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "SWAP_CENTER_BACK")
            .containsEntry("applied", false)
            .containsEntry("reason", "骰子條件未命中")
            .containsEntry("rawText", "サイコロを振る。センターホロメンとバックホロメンを交代する。");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void executeSwapCenterBackEffectShouldReturnNoOpWhenOpponentCannotBeResolved() throws Exception {
        MatchSwapCenterBackEffectExecutionService service = service(true, null, false);

        Map<String, Object> summary = service.executeSwapCenterBackEffect(
            1L,
            10L,
            "SWAP_CENTER_BACK",
            node("{\"rawText\":\"相手のセンターホロメンとバックホロメンを交代する。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "SWAP_CENTER_BACK")
            .containsEntry("applied", false)
            .containsEntry("reason", "找不到交換目標玩家");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapCenterBackEffectShouldReturnNoOpWhenCenterIsMissing() throws Exception {
        MatchSwapCenterBackEffectExecutionService service = service(true, 20L, false);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(null);

        Map<String, Object> summary = service.executeSwapCenterBackEffect(
            1L,
            10L,
            "SWAP_CENTER_BACK",
            node("{\"rawText\":\"センターホロメンとバックホロメンを交代する。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "沒有可交換的 CENTER");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapCenterBackEffectShouldUseNonRestedBackWhenRequiredByRawText() throws Exception {
        MatchSwapCenterBackEffectExecutionService service = service(true, 20L, false);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(100L);
        when(jdbcTemplate.query(contains("zone = 'BACK'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(true)))
            .thenReturn(200L);
        when(jdbcTemplate.update(contains("SET zone = 'BACK'"), eq(100L), eq(1L)))
            .thenReturn(1);
        when(jdbcTemplate.update(contains("SET zone = 'CENTER'"), eq(200L), eq(1L)))
            .thenReturn(1);

        Map<String, Object> summary = service.executeSwapCenterBackEffect(
            1L,
            10L,
            "SWAP_CENTER_BACK",
            node("{\"rawText\":\"お休みしていないバックホロメンとセンターホロメンを交代する。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", true)
            .containsEntry("fromCenterHolomemId", 100L)
            .containsEntry("fromBackHolomemId", 200L);
        verify(jdbcTemplate).query(contains("zone = 'BACK'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(true));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapCenterBackEffectShouldReturnNoOpWhenBackIsMissing() throws Exception {
        MatchSwapCenterBackEffectExecutionService service = service(true, 20L, false);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(100L);
        when(jdbcTemplate.query(contains("zone = 'BACK'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(false)))
            .thenReturn(null);

        Map<String, Object> summary = service.executeSwapCenterBackEffect(
            1L,
            10L,
            "SWAP_CENTER_BACK",
            node("{\"rawText\":\"センターホロメンとバックホロメンを交代する。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "沒有可交換的 BACK");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapCenterBackEffectShouldReturnNoOpWhenCenterIsActionLocked() throws Exception {
        MatchSwapCenterBackEffectExecutionService service = service(true, 20L, true);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(100L);

        Map<String, Object> summary = service.executeSwapCenterBackEffect(
            1L,
            10L,
            "SWAP_CENTER_BACK",
            node("{\"rawText\":\"センターホロメンとバックホロメンを交代する。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "目前效果限制：不可交代");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapCenterBackEffectShouldReturnNoOpWhenBackIsActionLocked() throws Exception {
        MatchSwapCenterBackEffectExecutionService service = service(
            true,
            20L,
            (matchId, userId, turnNumber, action, zone, holomemId) -> "BACK".equals(zone)
        );
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(100L);
        when(jdbcTemplate.query(contains("zone = 'BACK'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(false)))
            .thenReturn(200L);

        Map<String, Object> summary = service.executeSwapCenterBackEffect(
            1L,
            10L,
            "SWAP_CENTER_BACK",
            node("{\"rawText\":\"センターホロメンとバックホロメンを交代する。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "目前效果限制：不可交代");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapCenterBackEffectShouldSwapCenterAndBack() throws Exception {
        MatchSwapCenterBackEffectExecutionService service = service(true, 20L, false);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(100L);
        when(jdbcTemplate.query(contains("zone = 'BACK'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(false)))
            .thenReturn(200L);
        when(jdbcTemplate.update(contains("SET zone = 'BACK'"), eq(100L), eq(1L)))
            .thenReturn(1);
        when(jdbcTemplate.update(contains("SET zone = 'CENTER'"), eq(200L), eq(1L)))
            .thenReturn(1);

        Map<String, Object> summary = service.executeSwapCenterBackEffect(
            1L,
            10L,
            "SWAP_CENTER_BACK",
            node("{\"rawText\":\"センターホロメンとバックホロメンを交代する。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "SWAP_CENTER_BACK")
            .containsEntry("applied", true)
            .containsEntry("targetOwnerUserId", 10L)
            .containsEntry("fromCenterHolomemId", 100L)
            .containsEntry("fromBackHolomemId", 200L)
            .containsEntry("centerHolomemCardInstanceId", 2000L)
            .containsEntry("backHolomemCardInstanceId", 1000L);
        verify(jdbcTemplate).update(contains("SET zone = 'BACK'"), eq(100L), eq(1L));
        verify(jdbcTemplate).update(contains("SET zone = 'CENTER'"), eq(200L), eq(1L));
    }

    private MatchSwapCenterBackEffectExecutionService service(
        boolean diceApplies,
        Long opponentUserId,
        boolean actionLocked
    ) {
        return service(
            diceApplies,
            opponentUserId,
            (matchId, userId, turnNumber, action, zone, holomemId) -> actionLocked
        );
    }

    private MatchSwapCenterBackEffectExecutionService service(
        boolean diceApplies,
        Long opponentUserId,
        MatchSwapCenterBackEffectExecutionService.ActionLockChecker actionLockChecker
    ) {
        return new MatchSwapCenterBackEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            (rawText, effectNode, effectType) -> diceApplies,
            (matchId, userId) -> opponentUserId,
            matchId -> 3,
            actionLockChecker,
            matchHolomemId -> matchHolomemId * 10
        );
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
