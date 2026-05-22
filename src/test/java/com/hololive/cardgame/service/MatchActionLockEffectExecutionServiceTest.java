package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MatchActionLockEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void executeActionLockEffectShouldReturnNoOpWhenNoActionCanBeParsed() throws Exception {
        MatchActionLockEffectExecutionService service = service(20L, 3, 100L);

        Map<String, Object> summary = service.executeActionLockEffect(
            1L,
            10L,
            "ACTION_LOCK",
            node("{\"rawText\":\"このターンの間、何もしない。\"}"),
            "SELF",
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "ACTION_LOCK")
            .containsEntry("applied", false)
            .containsEntry("reason", "無可套用的行動封鎖條件");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void executeActionLockEffectShouldInsertOwnCenterAndCollabLock() throws Exception {
        MatchActionLockEffectExecutionService service = service(20L, 3, 100L);
        when(jdbcTemplate.update(
            contains("stat_type"),
            eq(1L),
            eq(10L),
            eq(10L),
            eq("DEBUFF"),
            eq(3),
            contains("\"BATON_TOUCH\"")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeActionLockEffect(
            1L,
            10L,
            "ACTION_LOCK",
            node("{\"rawText\":\"このターンの間、自分のセンターホロメンとコラボホロメンは、バトンタッチ、移動、交代できない。\"}"),
            "SELF",
            null
        );

        assertThat(summary)
            .containsEntry("effectType", "ACTION_LOCK")
            .containsEntry("applied", true)
            .containsEntry("statType", "ACTION_LOCK")
            .containsEntry("affectedUserId", 10L)
            .containsEntry("expiresTurn", 3);
        assertThat(summary.get("actions")).isEqualTo(List.of("BATON_TOUCH", "MOVE_STAGE", "SWAP"));
        assertThat(summary.get("zones")).isEqualTo(List.of("CENTER", "COLLAB"));
    }

    @Test
    void executeActionLockEffectShouldApplyOpponentNextTurnUnrestLockWithSpecificTarget() throws Exception {
        MatchActionLockEffectExecutionService service = service(20L, 3, 100L);
        when(jdbcTemplate.update(
            contains("stat_type"),
            eq(1L),
            eq(10L),
            eq(20L),
            eq("DEBUFF"),
            eq(4),
            contains("\"targetHolomemId\":100")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeActionLockEffect(
            1L,
            10L,
            "ACTION_LOCK",
            node("{\"rawText\":\"相手のセンターホロメンを選ぶ。選んだホロメンは、次の相手のリセットステップでアクティブにならない。\"}"),
            "OPPONENT",
            1000L
        );

        assertThat(summary)
            .containsEntry("applied", true)
            .containsEntry("statType", "ACTION_LOCK")
            .containsEntry("targetHolomemId", 100L)
            .containsEntry("targetHolomemCardInstanceId", 1000L)
            .containsEntry("affectedUserId", 20L)
            .containsEntry("expiresTurn", 4);
        assertThat(summary.get("actions")).isEqualTo(List.of("UNREST"));
        assertThat(summary.get("zones")).isEqualTo(List.of("CENTER"));
    }

    @Test
    void executeActionLockEffectShouldReturnNoOpWhenOpponentCannotBeResolved() throws Exception {
        MatchActionLockEffectExecutionService service = service(null, 3, 100L);

        Map<String, Object> summary = service.executeActionLockEffect(
            1L,
            10L,
            "ACTION_LOCK",
            node("{\"rawText\":\"相手のセンターホロメンは、交代できない。\"}"),
            "OPPONENT",
            null
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "找不到封鎖效果目標玩家");
        verifyNoInteractions(jdbcTemplate);
    }

    private MatchActionLockEffectExecutionService service(
        Long opponentUserId,
        int currentTurn,
        Long targetHolomemId
    ) {
        return new MatchActionLockEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            (matchId, userId) -> opponentUserId,
            matchId -> currentTurn,
            (matchId, userId, targetType, targetHolomemCardInstanceId, allowOpponent) -> targetHolomemId,
            matchHolomemId -> matchHolomemId * 10
        );
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
