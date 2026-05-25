package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchHealEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private Long targetHolomemId = 100L;
    private Long targetOwnerUserId = 20L;
    private Long targetCardInstanceId = 200L;
    private boolean hpChangeBlocked = false;

    private final MatchHealEffectExecutionService service = new MatchHealEffectExecutionService(
        jdbcTemplate,
        effectTextParser,
        (matchId, userId, targetType, targetHolomemCardInstanceId, defaultOpponent) -> targetHolomemId,
        (matchId, holomemId) -> targetOwnerUserId,
        holomemId -> targetCardInstanceId,
        (matchId, sourceUserId, ownerUserId, holomemId, effectType) -> hpChangeBlocked
    );

    @Test
    void executeHealEffectShouldRecoverDamageAndReturnSummary() throws Exception {
        whenDamageQueryReturns(80, 50);

        Map<String, Object> summary = service.executeHealEffect(
            1L,
            10L,
            "HEAL",
            node("{\"type\":\"HEAL\",\"value\":30}"),
            "SELF",
            200L
        );

        assertThat(summary).containsEntry("effectType", "HEAL");
        assertThat(summary).containsEntry("targetHolomemId", 100L);
        assertThat(summary).containsEntry("targetHolomemCardInstanceId", 200L);
        assertThat(summary).containsEntry("healRequested", 30);
        assertThat(summary).containsEntry("healApplied", 30);
        assertThat(summary).containsEntry("damageBefore", 80);
        assertThat(summary).containsEntry("damageAfter", 50);
        verify(jdbcTemplate).update(contains("GREATEST(COALESCE(damage_taken, 0) - ?"), eq(30), eq(100L), eq(1L), eq(20L));
    }

    @Test
    void executeHealEffectShouldNotApplyMoreThanExistingDamage() throws Exception {
        whenDamageQueryReturns(20, 0);

        Map<String, Object> summary = service.executeHealEffect(
            1L,
            10L,
            "HEAL",
            node("{\"type\":\"HEAL\",\"amount\":50}"),
            "SELF",
            null
        );

        assertThat(summary).containsEntry("healRequested", 50);
        assertThat(summary).containsEntry("healApplied", 20);
        assertThat(summary).containsEntry("damageBefore", 20);
        assertThat(summary).containsEntry("damageAfter", 0);
    }

    @Test
    void executeHealEffectShouldResolveHealValueFromRawText() throws Exception {
        whenDamageQueryReturns(40, 10);

        Map<String, Object> summary = service.executeHealEffect(
            1L,
            10L,
            "HEAL",
            node("{\"rawText\":\"自分のホロメンのHP30回復\"}"),
            "SELF",
            null
        );

        assertThat(summary).containsEntry("healRequested", 30);
        assertThat(summary).containsEntry("healApplied", 30);
    }

    @Test
    void executeHealEffectShouldReturnNoValueSummaryWhenHealValueMissing() throws Exception {
        Map<String, Object> summary = service.executeHealEffect(
            1L,
            10L,
            "HEAL",
            node("{\"type\":\"HEAL\"}"),
            "SELF",
            null
        );

        assertThat(summary).containsEntry("healRequested", 0);
        assertThat(summary).containsEntry("healApplied", 0);
        assertThat(summary).containsEntry("targetHolomemId", 100L);
        assertThat(summary).containsEntry("reason", "無可用回復數值");
    }

    @Test
    void executeHealEffectShouldReturnBlockedSummaryWhenHpChangeIsPrevented() throws Exception {
        hpChangeBlocked = true;

        Map<String, Object> summary = service.executeHealEffect(
            1L,
            10L,
            "HEAL",
            node("{\"heal\":30}"),
            "ENEMY",
            null
        );

        assertThat(summary).containsEntry("targetHolomemId", 100L);
        assertThat(summary).containsEntry("targetHolomemCardInstanceId", 200L);
        assertThat(summary).containsEntry("healRequested", 30);
        assertThat(summary).containsEntry("healApplied", 0);
        assertThat(summary).containsEntry("reason", "目標在相手のメインステップ中不受相手能力的 HP 變動影響");
    }

    @Test
    void executeHealEffectShouldThrowWhenTargetMissing() throws Exception {
        targetHolomemId = null;

        assertThatThrownBy(() ->
            service.executeHealEffect(1L, 10L, "HEAL", node("{\"value\":30}"), "SELF", null)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("HEAL 找不到可回復的 Holomen");
    }

    @SuppressWarnings("unchecked")
    private void whenDamageQueryReturns(Integer beforeDamage, Integer afterDamage) {
        when(
            jdbcTemplate.query(
                contains("SELECT damage_taken"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(1L),
                eq(20L)
            )
        ).thenReturn(beforeDamage);
        when(
            jdbcTemplate.query(
                contains("SELECT COALESCE(damage_taken, 0)"),
                any(ResultSetExtractor.class),
                eq(100L)
            )
        ).thenReturn(afterDamage);
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
