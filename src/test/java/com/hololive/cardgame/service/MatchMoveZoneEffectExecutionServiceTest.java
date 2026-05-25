package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchMoveZoneEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final GameActionExecutor gameActionExecutor = mock(GameActionExecutor.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void executeMoveZoneEffectShouldReturnNoOpWhenDiceConditionMisses() throws Exception {
        MatchMoveZoneEffectExecutionService service = service(false, false);

        Map<String, Object> summary = service.executeMoveZoneEffect(
            1L,
            10L,
            "MOVE_ZONE",
            node("{\"rawText\":\"サイコロを振る。バックポジションに移動\"}"),
            "ENEMY",
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "MOVE_ZONE")
            .containsEntry("applied", false)
            .containsEntry("reason", "骰子條件未命中");
        verifyNoInteractions(jdbcTemplate, gameActionExecutor);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveZoneEffectShouldMoveTargetToBackAndRest() throws Exception {
        MatchMoveZoneEffectExecutionService service = service(true, false);
        whenTargetHolomemRow(100L, 20L, "CENTER");
        when(jdbcTemplate.queryForObject(contains("zone = 'BACK'"), eq(Integer.class), eq(1L), eq(20L)))
            .thenReturn(1);
        when(gameActionExecutor.execute(any(), anyList()))
            .thenReturn(List.of(ActionResult.success("MOVE_ZONE", Map.of("rested", true))));

        Map<String, Object> summary = service.executeMoveZoneEffect(
            1L,
            10L,
            "MOVE_ZONE",
            node("{\"toZone\":\"BACK\",\"rawText\":\"相手をお休みさせてバックポジションに移動\"}"),
            "ENEMY",
            1000L
        );

        assertThat(summary)
            .containsEntry("effectType", "MOVE_ZONE")
            .containsEntry("targetHolomemId", 100L)
            .containsEntry("targetHolomemCardInstanceId", 1000L)
            .containsEntry("fromZone", "CENTER")
            .containsEntry("toZone", "BACK")
            .containsEntry("rested", true)
            .containsEntry("moved", true);
        verify(gameActionExecutor).execute(any(), anyList());
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveZoneEffectShouldPreferBackPositionPhraseOverCollabHeader() throws Exception {
        MatchMoveZoneEffectExecutionService service = service(true, false);
        whenTargetHolomemRow(100L, 20L, "COLLAB");
        when(jdbcTemplate.queryForObject(contains("zone = ?"), eq(Integer.class), eq(1L), eq(20L), eq("BACK")))
            .thenReturn(1);
        when(gameActionExecutor.execute(any(), anyList()))
            .thenReturn(List.of(ActionResult.success("MOVE_ZONE", Map.of("rested", false))));

        Map<String, Object> summary = service.executeMoveZoneEffect(
            1L,
            10L,
            "MOVE_ZONE",
            node("{\"rawText\":\"コラボエフェクト広がる地図：１の時、さらに、このホロメンをバックポジションに移動できる。\"}"),
            "SELF",
            1000L
        );

        assertThat(summary)
            .containsEntry("fromZone", "COLLAB")
            .containsEntry("toZone", "BACK")
            .containsEntry("moved", true);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveZoneEffectShouldFallbackToFirstBackHolomemWhenRawTextTargetsBackHolomem() throws Exception {
        MatchMoveZoneEffectExecutionService service = new MatchMoveZoneEffectExecutionService(
            jdbcTemplate,
            gameActionExecutor,
            effectTextParser,
            (rawText, effectNode, effectType) -> true,
            (matchId, userId, targetType, targetHolomemCardInstanceId, defaultOpponent) -> 300L,
            matchId -> 3,
            (matchId, userId, turnNumber, action, zone, holomemId) -> false,
            matchHolomemId -> matchHolomemId * 10
        );
        whenTargetHolomemRow(300L, 20L, "CENTER");
        when(jdbcTemplate.query(contains("zone = 'BACK'"), any(ResultSetExtractor.class), eq(1L), eq(20L)))
            .thenReturn(100L);
        whenTargetHolomemRow(100L, 20L, "BACK");
        when(jdbcTemplate.queryForObject(contains("zone = 'COLLAB'"), eq(Integer.class), eq(1L), eq(20L)))
            .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("zone = ?"), eq(Integer.class), eq(1L), eq(20L), eq("COLLAB")))
            .thenReturn(0);
        when(gameActionExecutor.execute(any(), anyList()))
            .thenReturn(List.of(ActionResult.success("MOVE_ZONE", Map.of("rested", false))));

        Map<String, Object> summary = service.executeMoveZoneEffect(
            1L,
            10L,
            "MOVE_ZONE",
            node("{\"rawText\":\"相手のコラボホロメンがいないなら、相手は、自身のバックホロメン1人をコラボポジションに移動させる。\"}"),
            "ENEMY",
            null
        );

        assertThat(summary)
            .containsEntry("targetHolomemId", 100L)
            .containsEntry("targetHolomemCardInstanceId", 1000L)
            .containsEntry("fromZone", "BACK")
            .containsEntry("toZone", "COLLAB")
            .containsEntry("moved", true);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveZoneEffectShouldReturnNoOpWhenActionLockIsActive() throws Exception {
        MatchMoveZoneEffectExecutionService service = service(true, true);
        whenTargetHolomemRow(100L, 20L, "CENTER");

        Map<String, Object> summary = service.executeMoveZoneEffect(
            1L,
            10L,
            "MOVE_ZONE",
            node("{\"toZone\":\"BACK\",\"rawText\":\"バックポジションに移動\"}"),
            "ENEMY",
            1000L
        );

        assertThat(summary)
            .containsEntry("effectType", "MOVE_ZONE")
            .containsEntry("applied", false)
            .containsEntry("reason", "目前效果限制：不可移動");
        verifyNoInteractions(gameActionExecutor);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveZoneEffectShouldReturnNoOpWhenTargetAlreadyInDestination() throws Exception {
        MatchMoveZoneEffectExecutionService service = service(true, false);
        whenTargetHolomemRow(100L, 20L, "BACK");

        Map<String, Object> summary = service.executeMoveZoneEffect(
            1L,
            10L,
            "MOVE_ZONE",
            node("{\"toZone\":\"BACK\"}"),
            "ENEMY",
            1000L
        );

        assertThat(summary)
            .containsEntry("effectType", "MOVE_ZONE")
            .containsEntry("targetHolomemId", 100L)
            .containsEntry("fromZone", "BACK")
            .containsEntry("toZone", "BACK")
            .containsEntry("moved", false)
            .containsEntry("reason", "目標已在同區域");
        verifyNoInteractions(gameActionExecutor);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeMoveZoneEffectShouldFallbackToSqlUpdateWhenActionPipelineFails() throws Exception {
        MatchMoveZoneEffectExecutionService service = service(true, false);
        whenTargetHolomemRow(100L, 20L, "CENTER");
        when(jdbcTemplate.queryForObject(contains("zone = 'BACK'"), eq(Integer.class), eq(1L), eq(20L)))
            .thenReturn(1);
        when(gameActionExecutor.execute(any(), anyList()))
            .thenReturn(List.of(ActionResult.failure("MOVE_ZONE", "HOLOMEM_NOT_MOVED")));
        when(jdbcTemplate.update(contains("UPDATE match_holomems"), eq("BACK"), eq(true), eq(100L), eq(1L)))
            .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT is_rested"), any(ResultSetExtractor.class), eq(100L), eq(1L)))
            .thenReturn(true);

        Map<String, Object> summary = service.executeMoveZoneEffect(
            1L,
            10L,
            "MOVE_ZONE",
            node("{\"toZone\":\"BACK\",\"rawText\":\"お休みしてバックポジションに移動\"}"),
            "ENEMY",
            1000L
        );

        assertThat(summary)
            .containsEntry("effectType", "MOVE_ZONE")
            .containsEntry("fromZone", "CENTER")
            .containsEntry("toZone", "BACK")
            .containsEntry("rested", true)
            .containsEntry("moved", true);
        verify(jdbcTemplate).update(contains("UPDATE match_holomems"), eq("BACK"), eq(true), eq(100L), eq(1L));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void whenTargetHolomemRow(Long holomemId, Long ownerUserId, String zone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("owner_user_id", ownerUserId);
        row.put("zone", zone);
        when(jdbcTemplate.query(contains("SELECT owner_user_id, zone"), any(ResultSetExtractor.class), eq(holomemId), eq(1L)))
            .thenReturn(row);
    }

    private MatchMoveZoneEffectExecutionService service(boolean diceApplies, boolean actionLockActive) {
        return new MatchMoveZoneEffectExecutionService(
            jdbcTemplate,
            gameActionExecutor,
            effectTextParser,
            (rawText, effectNode, effectType) -> diceApplies,
            (matchId, userId, targetType, targetHolomemCardInstanceId, defaultOpponent) -> 100L,
            matchId -> 3,
            (matchId, userId, turnNumber, action, zone, holomemId) -> actionLockActive,
            matchHolomemId -> 1000L
        );
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
