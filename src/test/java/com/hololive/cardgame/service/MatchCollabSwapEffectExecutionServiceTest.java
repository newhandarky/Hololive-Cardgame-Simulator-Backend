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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchCollabSwapEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapWithCollabEffectShouldSwapExplicitSourceWithCollab() throws Exception {
        MatchCollabSwapEffectExecutionService service = service(100L);
        when(jdbcTemplate.query(contains("WHERE h.id = ?"), any(ResultSetExtractor.class), eq(100L), eq(1L), eq(10L)))
            .thenReturn(source(100L, "BACK", 120));
        when(jdbcTemplate.query(contains("h.zone = 'COLLAB'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(false)))
            .thenReturn(collab(200L, 2000L, 90));

        Map<String, Object> summary = service.executeSwapWithCollabEffect(
            1L,
            10L,
            "SWAP_WITH_COLLAB",
            node("{\"rawText\":\"自分のコラボホロメンとこのホロメンを交代できる。\"}"),
            1000L
        );

        assertThat(summary)
            .containsEntry("effectType", "SWAP_WITH_COLLAB")
            .containsEntry("swapped", true)
            .containsEntry("sourceHolomemId", 100L)
            .containsEntry("targetHolomemId", 200L)
            .containsEntry("sourceHolomemCardInstanceId", 1000L)
            .containsEntry("targetHolomemCardInstanceId", 2000L)
            .containsEntry("requireLowHpCollab", false);
        verify(jdbcTemplate).update(contains("SET zone = 'COLLAB'"), eq(100L), eq(1L), eq(10L));
        verify(jdbcTemplate).update(contains("SET zone = 'BACK'"), eq(200L), eq(1L), eq(10L));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapWithCollabEffectShouldFallbackToFirstBackWhenSourceIsNotSpecified() throws Exception {
        MatchCollabSwapEffectExecutionService service = service(null);
        when(jdbcTemplate.query(contains("zone = 'BACK'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(100L);
        when(jdbcTemplate.query(contains("WHERE h.id = ?"), any(ResultSetExtractor.class), eq(100L), eq(1L), eq(10L)))
            .thenReturn(source(100L, "BACK", 120));
        when(jdbcTemplate.query(contains("h.zone = 'COLLAB'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(false)))
            .thenReturn(collab(200L, 2000L, 90));

        Map<String, Object> summary = service.executeSwapWithCollabEffect(
            1L,
            10L,
            "SWAP_WITH_COLLAB",
            node("{\"rawText\":\"自分のコラボホロメンとこのホロメンを交代できる。\"}"),
            null
        );

        assertThat(summary)
            .containsEntry("swapped", true)
            .containsEntry("sourceHolomemId", 100L)
            .containsEntry("targetHolomemId", 200L);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapWithCollabEffectShouldReturnNoOpWhenSourceCannotBeResolved() throws Exception {
        MatchCollabSwapEffectExecutionService service = service(null);
        when(jdbcTemplate.query(contains("zone = 'BACK'"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(null);

        Map<String, Object> summary = service.executeSwapWithCollabEffect(
            1L,
            10L,
            "SWAP_WITH_COLLAB",
            node("{\"rawText\":\"自分のコラボホロメンとこのホロメンを交代できる。\"}"),
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "SWAP_WITH_COLLAB")
            .containsEntry("applied", false)
            .containsEntry("reason", "找不到可交換的來源 Holomem");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapWithCollabEffectShouldReturnNoOpWhenSourceDoesNotExist() throws Exception {
        MatchCollabSwapEffectExecutionService service = service(100L);
        when(jdbcTemplate.query(contains("WHERE h.id = ?"), any(ResultSetExtractor.class), eq(100L), eq(1L), eq(10L)))
            .thenReturn(null);

        Map<String, Object> summary = service.executeSwapWithCollabEffect(
            1L,
            10L,
            "SWAP_WITH_COLLAB",
            node("{\"rawText\":\"自分のコラボホロメンとこのホロメンを交代できる。\"}"),
            1000L
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "來源 Holomem 不存在");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapWithCollabEffectShouldReturnNoOpWhenBackLimitedSourceIsNotBack() throws Exception {
        MatchCollabSwapEffectExecutionService service = service(100L);
        when(jdbcTemplate.query(contains("WHERE h.id = ?"), any(ResultSetExtractor.class), eq(100L), eq(1L), eq(10L)))
            .thenReturn(source(100L, "CENTER", 120));

        Map<String, Object> summary = service.executeSwapWithCollabEffect(
            1L,
            10L,
            "SWAP_WITH_COLLAB",
            node("{\"rawText\":\"[バックポジション限定]自分のコラボホロメンとこのホロメンを交代できる。\"}"),
            1000L
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "來源 Holomem 不在 BACK，無法交換");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapWithCollabEffectShouldFilterLowHpCollabWhenRequiredByRawText() throws Exception {
        MatchCollabSwapEffectExecutionService service = service(100L);
        when(jdbcTemplate.query(contains("WHERE h.id = ?"), any(ResultSetExtractor.class), eq(100L), eq(1L), eq(10L)))
            .thenReturn(source(100L, "BACK", 120));
        when(jdbcTemplate.query(contains("h.zone = 'COLLAB'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(true)))
            .thenReturn(collab(200L, 2000L, 70));

        Map<String, Object> summary = service.executeSwapWithCollabEffect(
            1L,
            10L,
            "SWAP_WITH_COLLAB",
            node("{\"rawText\":\"自分の残りHP70以下のコラボホロメンとこのホロメンを交代できる。\"}"),
            1000L
        );

        assertThat(summary)
            .containsEntry("swapped", true)
            .containsEntry("targetHolomemId", 200L)
            .containsEntry("requireLowHpCollab", true);
        verify(jdbcTemplate).query(contains("h.zone = 'COLLAB'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(true));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeSwapWithCollabEffectShouldReturnNoOpWhenCollabTargetIsMissing() throws Exception {
        MatchCollabSwapEffectExecutionService service = service(100L);
        when(jdbcTemplate.query(contains("WHERE h.id = ?"), any(ResultSetExtractor.class), eq(100L), eq(1L), eq(10L)))
            .thenReturn(source(100L, "BACK", 120));
        when(jdbcTemplate.query(contains("h.zone = 'COLLAB'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(true)))
            .thenReturn(null);

        Map<String, Object> summary = service.executeSwapWithCollabEffect(
            1L,
            10L,
            "SWAP_WITH_COLLAB",
            node("{\"rawText\":\"自分の残りHP70以下のコラボホロメンとこのホロメンを交代できる。\"}"),
            1000L
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "沒有符合條件的 COLLAB 目標可交換");
    }

    private MatchCollabSwapEffectExecutionService service(Long resolvedSourceHolomemId) {
        return new MatchCollabSwapEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            (matchId, userId, targetHolomemCardInstanceId) -> resolvedSourceHolomemId,
            matchHolomemId -> matchHolomemId * 10
        );
    }

    private Map<String, Object> source(Long id, String zone, int remainHp) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("zone", zone);
        row.put("remain_hp", remainHp);
        return row;
    }

    private Map<String, Object> collab(Long id, Long matchCardId, int remainHp) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("match_card_id", matchCardId);
        row.put("remain_hp", remainHp);
        return row;
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
