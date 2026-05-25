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
import com.hololive.cardgame.game.action.EffectResolver;
import com.hololive.cardgame.game.action.GameActionExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchEffectDamageExecutionCharacterizationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private MatchEffectService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new MatchEffectService(
            jdbcTemplate,
            objectMapper,
            mock(DiceService.class),
            mock(EffectResolver.class),
            mock(GameActionExecutor.class)
        );
    }

    @Test
    void executeDamageEffectShouldApplyRawTextDamageAndReturnNonDownSummary() throws Exception {
        stubDefaultTargetResolution();
        stubCurrentTurn(3);
        stubDamageModifier(0);
        stubTargetZone("CENTER");
        stubTargetState(120);
        stubMemberHp(200);
        stubNoAttachedSupportHpBonus();
        stubNoPassiveGiftHpBonus();

        Map<String, Object> summary = service.executeDamageEffect(
            1L,
            10L,
            "ART_DAMAGE",
            node("{\"rawText\":\"相手のホロメンにダメージ120を与える。\"}"),
            "ENEMY",
            null
        );

        assertThat(summary).containsEntry("effectType", "ART_DAMAGE");
        assertThat(summary).containsEntry("targetHolomemId", 100L);
        assertThat(summary).containsEntry("damageRequested", 120);
        assertThat(summary).containsEntry("damageApplied", 120);
        assertThat(summary).containsEntry("baseDamage", 120);
        assertThat(summary).containsEntry("damageModifierApplied", 0);
        assertThat(summary).containsEntry("targetBaseHp", 200);
        assertThat(summary).containsEntry("targetHp", 200);
        assertThat(summary).containsEntry("targetDamageTaken", 120);
        assertThat(summary).containsEntry("downed", false);
        assertThat(summary).containsEntry("lifeReduced", false);
        verify(jdbcTemplate).update(
            contains("SET damage_taken = COALESCE(damage_taken, 0) + ?"),
            eq(120),
            eq(100L),
            eq(1L),
            eq(20L)
        );
    }

    @Test
    void executeDamageEffectShouldReturnNoOpWhenModifierReducesDamageToZero() throws Exception {
        stubDefaultTargetResolution();
        stubCurrentTurn(3);
        stubDamageModifier(-70);

        Map<String, Object> summary = service.executeDamageEffect(
            1L,
            10L,
            "ART_DAMAGE",
            node("{\"value\":50}"),
            "ENEMY",
            null
        );

        assertThat(summary).containsEntry("effectType", "ART_DAMAGE");
        assertThat(summary).containsEntry("damageRequested", 50);
        assertThat(summary).containsEntry("damageApplied", 0);
        assertThat(summary).containsEntry("baseDamage", 50);
        assertThat(summary).containsEntry("damageModifierApplied", -70);
        assertThat(summary).containsEntry("targetHolomemId", 100L);
        assertThat(summary).containsEntry("downed", false);
        assertThat(summary).containsEntry("lifeReduced", false);
        assertThat(summary).containsEntry("reason", "修正後傷害小於等於 0");
        verify(jdbcTemplate, never()).update(
            contains("SET damage_taken = COALESCE(damage_taken, 0) + ?"),
            any(),
            any(),
            any(),
            any()
        );
    }

    @Test
    void executeDamageEffectShouldReturnNoValueSummaryWhenDamageValueMissing() throws Exception {
        stubDefaultTargetResolution();

        Map<String, Object> summary = service.executeDamageEffect(
            1L,
            10L,
            "DAMAGE",
            node("{\"type\":\"DAMAGE\"}"),
            "ENEMY",
            null
        );

        assertThat(summary).containsEntry("effectType", "DAMAGE");
        assertThat(summary).containsEntry("damageRequested", 0);
        assertThat(summary).containsEntry("damageApplied", 0);
        assertThat(summary).containsEntry("baseDamage", 0);
        assertThat(summary).containsEntry("damageModifierApplied", 0);
        assertThat(summary).containsEntry("targetHolomemId", 100L);
        assertThat(summary).containsEntry("downed", false);
        assertThat(summary).containsEntry("lifeReduced", false);
        assertThat(summary).containsEntry("reason", "無可用傷害數值");
        verify(jdbcTemplate, never()).update(
            contains("SET damage_taken = COALESCE(damage_taken, 0) + ?"),
            any(),
            any(),
            any(),
            any()
        );
    }

    @SuppressWarnings("unchecked")
    private void stubDefaultTargetResolution() {
        when(jdbcTemplate.query(contains("SELECT player_a_id, player_b_id"), any(ResultSetExtractor.class), eq(1L)))
            .thenReturn(20L);
        when(jdbcTemplate.query(contains("zone = 'CENTER'"), any(ResultSetExtractor.class), eq(1L), eq(20L)))
            .thenReturn(100L);
        when(jdbcTemplate.query(contains("SELECT owner_user_id"), any(ResultSetExtractor.class), eq(100L), eq(1L)))
            .thenReturn(20L);
    }

    @SuppressWarnings("unchecked")
    private void stubCurrentTurn(int turnNumber) {
        when(jdbcTemplate.query(contains("SELECT turn_number"), any(ResultSetExtractor.class), eq(1L)))
            .thenReturn(turnNumber);
    }

    @SuppressWarnings("unchecked")
    private void stubDamageModifier(int modifier) {
        when(
            jdbcTemplate.query(
                contains("FROM match_turn_effects"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(10L),
                eq(3)
            )
        ).thenReturn(modifier);
    }

    @SuppressWarnings("unchecked")
    private void stubTargetZone(String zone) {
        when(jdbcTemplate.query(contains("SELECT zone"), any(ResultSetExtractor.class), eq(100L), eq(1L)))
            .thenReturn(zone);
    }

    @SuppressWarnings("unchecked")
    private void stubTargetState(int damageTaken) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", 100L);
        state.put("match_card_id", 200L);
        state.put("card_id", "TARGET_CARD");
        state.put("zone", "CENTER");
        state.put("damage_taken", damageTaken);
        when(
            jdbcTemplate.query(
                contains("SELECT id, match_card_id, card_id, zone, damage_taken"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(1L),
                eq(20L)
            )
        ).thenReturn(state);
    }

    @SuppressWarnings("unchecked")
    private void stubMemberHp(int hp) {
        when(jdbcTemplate.query(contains("SELECT hp FROM member_cards"), any(ResultSetExtractor.class), eq("TARGET_CARD")))
            .thenReturn(hp);
    }

    @SuppressWarnings("unchecked")
    private void stubNoAttachedSupportHpBonus() {
        when(
            jdbcTemplate.query(
                contains("FROM match_holomem_supports hs"),
                any(RowMapper.class),
                eq(100L),
                eq(1L)
            )
        ).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubNoPassiveGiftHpBonus() {
        when(
            jdbcTemplate.query(
                contains("SELECT h.id,\n                   h.zone,\n                   h.current_level,\n                   mp.current_life"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(20L),
                eq(100L)
            )
        ).thenReturn(null);
        when(
            jdbcTemplate.query(
                contains("mc.passive_effect_json::text AS passive_effect_json_text"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(20L),
                eq(100L)
            )
        ).thenReturn(null);
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
