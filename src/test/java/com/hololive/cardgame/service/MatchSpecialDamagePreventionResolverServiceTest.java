package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchSpecialDamagePreventionResolverServiceTest {

    private static final String HSD13012_GIFT_TEXT =
        "{\"ギフト\":\"自分のバックホロメンが相手から特殊ダメージを受ける時、このターンの間、自分のバックホロメン全員は特殊ダメージを受けない。\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchGiftTriggerConditionService giftTriggerConditionService = new MatchGiftTriggerConditionService(
        jdbcTemplate,
        effectTextParser,
        new GiftTriggerMatcher(),
        new SearchCriteriaParser(jdbcTemplate, effectTextParser)
    );
    private final MatchSpecialDamagePreventionResolverService service = new MatchSpecialDamagePreventionResolverService(
        jdbcTemplate,
        effectTextParser,
        giftTriggerConditionService
    );

    @Test
    @SuppressWarnings("unchecked")
    void isSpecialDamageImmunityActiveShouldReturnTrueForBackTurnEffect() {
        when(
            jdbcTemplate.query(
                contains("payload::text LIKE '%\"SPECIAL_DAMAGE_IMMUNITY\"%'"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(20L),
                eq(3)
            )
        ).thenReturn(1);

        boolean active = service.isSpecialDamageImmunityActive(1L, 20L, 3, "BACK");

        assertThat(active).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void isSpecialDamageImmunityActiveShouldSkipNonBackTarget() {
        boolean active = service.isSpecialDamageImmunityActive(1L, 20L, 3, "CENTER");

        assertThat(active).isFalse();
        verify(jdbcTemplate, never()).query(
            contains("payload::text LIKE '%\"SPECIAL_DAMAGE_IMMUNITY\"%'"),
            any(ResultSetExtractor.class),
            any(),
            any(),
            any()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryActivateShouldArchiveStackCardAndCreateTurnEffectForHsd13012() {
        whenOpponentTurn(10L);
        whenHsd13012Holder();
        whenStackCard(900L);
        whenArchiveOrder(5);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(5), eq(900L), eq(1L), eq(20L))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO match_turn_effects"), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);

        Map<String, Object> summary = service.tryActivateHsd13012SpecialDamageImmunity(
            1L,
            10L,
            20L,
            100L,
            "BACK",
            3
        );

        assertThat(summary).containsEntry("triggerType", "SPECIAL_DAMAGE_RECEIVED");
        assertThat(summary).containsEntry("preventedDamage", true);
        assertThat(summary).containsEntry("holderHolomemId", 700L);
        assertThat(summary).containsEntry("holderCardInstanceId", 800L);
        assertThat(summary).containsEntry("holderCardId", "HSD13-012");
        assertThat(summary).containsEntry("archivedStackCardInstanceId", 900L);
        assertThat(summary).containsEntry("expiresTurn", 3);
        assertThat(summary).containsEntry("targetHolomemId", 100L);
        verify(jdbcTemplate).update(
            contains("DELETE FROM match_holomem_stack_cards"),
            eq(700L),
            eq(900L)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryActivateShouldNotCreateTurnEffectWithoutStackCost() {
        whenOpponentTurn(10L);
        whenHsd13012Holder();
        whenStackCard(null);

        Map<String, Object> summary = service.tryActivateHsd13012SpecialDamageImmunity(
            1L,
            10L,
            20L,
            100L,
            "BACK",
            3
        );

        assertThat(summary).isNull();
        verify(jdbcTemplate, never()).update(contains("UPDATE match_cards"), any(), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO match_turn_effects"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void tryActivateShouldSkipSelfDamage() {
        Map<String, Object> summary = service.tryActivateHsd13012SpecialDamageImmunity(
            1L,
            20L,
            20L,
            100L,
            "BACK",
            3
        );

        assertThat(summary).isNull();
        verify(jdbcTemplate, never()).queryForList(contains("h.card_id = 'HSD13-012'"), eq(1L), eq(20L));
    }

    @Test
    void tryActivateShouldSkipNonBackTarget() {
        Map<String, Object> summary = service.tryActivateHsd13012SpecialDamageImmunity(
            1L,
            10L,
            20L,
            100L,
            "CENTER",
            3
        );

        assertThat(summary).isNull();
        verify(jdbcTemplate, never()).queryForList(contains("h.card_id = 'HSD13-012'"), eq(1L), eq(20L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryActivateShouldSkipWhenItIsNotOpponentTurnForDefender() {
        whenOpponentTurn(20L);

        Map<String, Object> summary = service.tryActivateHsd13012SpecialDamageImmunity(
            1L,
            10L,
            20L,
            100L,
            "BACK",
            3
        );

        assertThat(summary).isNull();
        verify(jdbcTemplate, never()).queryForList(contains("h.card_id = 'HSD13-012'"), eq(1L), eq(20L));
    }

    @SuppressWarnings("unchecked")
    private void whenOpponentTurn(Long currentTurnPlayerId) {
        when(
            jdbcTemplate.query(
                contains("SELECT current_turn_player_id"),
                any(ResultSetExtractor.class),
                eq(1L)
            )
        ).thenReturn(currentTurnPlayerId);
    }

    private void whenHsd13012Holder() {
        when(jdbcTemplate.queryForList(contains("h.card_id = 'HSD13-012'"), eq(1L), eq(20L)))
            .thenReturn(List.of(Map.of(
                "holomem_id",
                700L,
                "match_card_id",
                800L,
                "card_id",
                "HSD13-012",
                "passive_text",
                HSD13012_GIFT_TEXT
            )));
    }

    @SuppressWarnings("unchecked")
    private void whenStackCard(Long stackCardInstanceId) {
        when(
            jdbcTemplate.query(
                contains("FROM match_holomem_stack_cards s"),
                any(ResultSetExtractor.class),
                eq(700L),
                eq(800L)
            )
        ).thenReturn(stackCardInstanceId);
    }

    private void whenArchiveOrder(int archiveOrder) {
        when(
            jdbcTemplate.queryForObject(
                contains("SELECT COALESCE(MAX(order_index), 0) + 1"),
                eq(Integer.class),
                eq(1L),
                eq(20L),
                eq("ARCHIVE")
            )
        ).thenReturn(archiveOrder);
    }
}
