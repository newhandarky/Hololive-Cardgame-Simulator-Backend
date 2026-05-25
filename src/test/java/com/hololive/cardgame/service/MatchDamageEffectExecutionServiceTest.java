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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchDamageEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    @SuppressWarnings("unchecked")
    void executeDamageEffectShouldApplyDamageAndReturnNonDownSummary() throws Exception {
        MatchDamageEffectExecutionService service = service(
            false,
            new MatchDamageEffectExecutionService.EffectiveHp(200, 0, 200),
            Map.of("triggered", false)
        );
        when(
            jdbcTemplate.query(
                contains("SELECT id, match_card_id, card_id, zone, damage_taken"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(1L),
                eq(20L)
            )
        ).thenReturn(holomemState("TARGET_CARD", "CENTER", 120));

        Map<String, Object> summary = service.executeDamageEffect(
            1L,
            10L,
            "ART_DAMAGE",
            node("{\"value\":120}"),
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
        assertThat(summary).containsEntry("targetAttachedSupportHpBonus", 0);
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
    @SuppressWarnings("unchecked")
    void executeDamageEffectShouldArchiveAndReduceLifeWhenDamageDownsCenter() throws Exception {
        MatchDamageEffectExecutionService service = service(
            false,
            new MatchDamageEffectExecutionService.EffectiveHp(200, 10, 210),
            Map.of("triggered", false)
        );
        when(
            jdbcTemplate.query(
                contains("SELECT id, match_card_id, card_id, zone, damage_taken"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(1L),
                eq(20L)
            )
        ).thenReturn(holomemState("TARGET_CARD", "CENTER", 210));
        when(
            jdbcTemplate.queryForObject(
                contains("SELECT COALESCE(MAX(order_index), 0) + 1"),
                eq(Integer.class),
                eq(1L),
                eq(20L),
                eq("ARCHIVE")
            )
        ).thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> summary = service.executeDamageEffect(
            1L,
            10L,
            "ART_DAMAGE",
            node("{\"value\":210}"),
            "ENEMY",
            null
        );

        assertThat(summary).containsEntry("downed", true);
        assertThat(summary).containsEntry("targetBaseHp", 200);
        assertThat(summary).containsEntry("targetAttachedSupportHpBonus", 10);
        assertThat(summary).containsEntry("targetHp", 210);
        assertThat(summary).containsEntry("archivedCheerCardInstanceIds", List.of(301L));
        assertThat(summary).containsEntry("archivedSupportCardInstanceIds", List.of(401L));
        assertThat(summary).containsEntry("archivedHolomemCardInstanceIds", List.of());
        assertThat(summary).containsEntry("lifeReduced", true);
        assertThat(summary).containsEntry("lostLifeCardInstanceId", 701L);
        assertThat(summary).containsEntry("lostLifeCardInstanceIds", List.of(701L));
        verify(jdbcTemplate).update("DELETE FROM match_holomems WHERE id = ? AND match_id = ?", 100L, 1L);
    }

    private MatchDamageEffectExecutionService service(
        boolean specialDamagePrevented,
        MatchDamageEffectExecutionService.EffectiveHp effectiveHp,
        Map<String, Object> downEventSummary
    ) {
        return new MatchDamageEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            (matchId, userId, targetType, targetCardInstanceId, allowFallback) -> 100L,
            (matchId, holomemId) -> 20L,
            matchId -> 3,
            (matchId, affectedUserId, currentTurn) -> 0,
            (matchId, sourceUserId, targetOwnerUserId, targetHolomemId, effectType) -> false,
            (matchId, holomemId) -> "CENTER",
            (matchId, affectedUserId, currentTurn, targetZone) -> specialDamagePrevented,
            (matchId, sourceUserId, defendingUserId, targetHolomemId, targetZone, currentTurn) -> null,
            (matchId, ownerUserId, holomemId, cardId) -> effectiveHp,
            (matchId, holomemId, ownerUserId) -> List.of(301L),
            (matchId, holomemId, ownerUserId) -> List.of(401L),
            (matchId, holomemId, ownerUserId) -> List.of(),
            effectNode -> false,
            (matchId, ownerUserId) -> 701L,
            (matchId, actorUserId, downedOwnerUserId, downedCardId, currentTurn, applyLifeLoss, downedStageZone) -> downEventSummary,
            effectSummary -> List.of()
        );
    }

    private Map<String, Object> holomemState(String cardId, String zone, int damageTaken) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", 100L);
        state.put("match_card_id", 200L);
        state.put("card_id", cardId);
        state.put("zone", zone);
        state.put("damage_taken", damageTaken);
        return state;
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
