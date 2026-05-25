package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchDamageEffectiveHpResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchDamageEffectiveHpResolverService service = new MatchDamageEffectiveHpResolverService(
        jdbcTemplate,
        objectMapper,
        effectTextParser
    );

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldIncludeAttachedSupportHpBonus() {
        whenBaseHp(200);
        whenAttachedSupportEffects(List.of("{\"rawText\":\"このマスコットが付いているホロメンのHP+20\"}"));
        whenNoPassiveGiftHpBonus();

        MatchDamageEffectExecutionService.EffectiveHp hp = service.resolve(1L, 20L, 100L, "TARGET_CARD");

        assertThat(hp.baseHp()).isEqualTo(200);
        assertThat(hp.attachedSupportHpBonus()).isEqualTo(20);
        assertThat(hp.totalHp()).isEqualTo(220);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldIncludeSelfPassiveGiftHpBonusPerAttachedCheer() {
        whenBaseHp(200);
        whenAttachedSupportEffects(List.of());
        whenPassiveGiftHpTarget(3);
        whenPassiveGiftHolder("{\"キーワード\":\"このホロメンのエール1枚につきHP+10\"}");

        MatchDamageEffectExecutionService.EffectiveHp hp = service.resolve(1L, 20L, 100L, "TARGET_CARD");

        assertThat(hp.baseHp()).isEqualTo(200);
        assertThat(hp.attachedSupportHpBonus()).isZero();
        assertThat(hp.totalHp()).isEqualTo(230);
    }

    @SuppressWarnings("unchecked")
    private void whenBaseHp(int hp) {
        when(jdbcTemplate.query(contains("SELECT hp FROM member_cards"), any(ResultSetExtractor.class), eq("TARGET_CARD")))
            .thenReturn(hp);
    }

    @SuppressWarnings("unchecked")
    private void whenAttachedSupportEffects(List<String> effectJsonTexts) {
        when(
            jdbcTemplate.query(
                contains("FROM match_holomem_supports hs"),
                any(RowMapper.class),
                eq(100L),
                eq(1L)
            )
        ).thenReturn(effectJsonTexts);
    }

    @SuppressWarnings("unchecked")
    private void whenNoPassiveGiftHpBonus() {
        when(
            jdbcTemplate.query(
                contains("attached_cheer_count"),
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

    @SuppressWarnings("unchecked")
    private void whenPassiveGiftHpTarget(int attachedCheerCount) {
        when(
            jdbcTemplate.query(
                contains("attached_cheer_count"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(20L),
                eq(100L)
            )
        ).thenReturn(new MatchDamageEffectiveHpResolverService.PassiveGiftHpTargetContext(
            100L,
            "CENTER",
            "DEBUT",
            Set.of(),
            attachedCheerCount
        ));
    }

    @SuppressWarnings("unchecked")
    private void whenPassiveGiftHolder(String passiveEffectJsonText) {
        when(
            jdbcTemplate.query(
                contains("mc.passive_effect_json::text AS passive_effect_json_text"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(20L),
                eq(100L)
            )
        ).thenReturn(new MatchDamageEffectiveHpResolverService.PassiveGiftHolderContext(
            100L,
            "CENTER",
            passiveEffectJsonText
        ));
    }
}
