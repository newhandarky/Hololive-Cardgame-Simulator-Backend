package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchTriggeredGiftDamagePreventionExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DiceService diceService = mock(DiceService.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchGiftTriggerConditionService giftTriggerConditionService = new MatchGiftTriggerConditionService(
        jdbcTemplate,
        effectTextParser,
        new GiftTriggerMatcher(),
        new SearchCriteriaParser(jdbcTemplate, effectTextParser)
    );
    private final MatchTriggeredGiftDamagePreventionExecutionService service =
        new MatchTriggeredGiftDamagePreventionExecutionService(
            jdbcTemplate,
            effectTextParser,
            diceService,
            new GiftTriggerMatcher(),
            new GiftTurnUsageReader(jdbcTemplate),
            giftTriggerConditionService
        );

    @Test
    void resolveShouldReturnNullWhenContextIsIncomplete() {
        Map<String, Object> summary = service.resolveTriggeredGiftDamagePrevention(
            100L,
            20L,
            10L,
            501L,
            801L,
            2,
            0
        );

        assertThat(summary).isNull();
        verifyNoInteractions(jdbcTemplate, diceService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldPreventDamageWhenDiceConditionMatches() {
        when(
            jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("FROM match_holomems"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq(801L)
            )
        ).thenReturn(row(
            "id", 701L,
            "match_card_id", 801L,
            "zone", "CENTER",
            "current_level", "DEBUT"
        ));
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("m.passive_effect_json"), eq(100L), eq(20L)))
            .thenReturn(List.of(row(
                "holomem_id", 901L,
                "match_card_id", 902L,
                "card_id", "HBP01-027",
                "zone", "COLLAB",
                "current_level", "FIRST",
                "passive_text", "{\"キーワード\":\"ギフト：ターンに1回、自分のホロメンがダメージを受ける時、サイコロを1回振れる。奇数の時、そのダメージを受けない。\"}"
            )));
        when(diceService.rollD6()).thenReturn(1);

        Map<String, Object> summary = service.resolveTriggeredGiftDamagePrevention(
            100L,
            20L,
            10L,
            501L,
            801L,
            2,
            120
        );

        assertThat(summary)
            .containsEntry("triggerType", "DAMAGE_RECEIVED")
            .containsEntry("giftHolderHolomemId", 901L)
            .containsEntry("giftHolderCardInstanceId", 902L)
            .containsEntry("giftHolderCardId", "HBP01-027")
            .containsEntry("giftHolderZone", "COLLAB")
            .containsEntry("sourceCardInstanceId", 501L)
            .containsEntry("triggerTargetCardInstanceId", 801L)
            .containsEntry("incomingDamage", 120)
            .containsEntry("damageAfter", 0)
            .containsEntry("applied", true)
            .containsEntry("preventedDamage", true)
            .containsEntry("diceRoll", 1)
            .containsEntry("diceMatched", true);
        assertThat(summary.get("requestedEffects")).isEqualTo(List.of("PREVENT_DAMAGE"));
        assertThat(summary.get("unsupportedEffects")).isEqualTo(List.of());
        assertThat(summary.get("skippedEffects")).isEqualTo(List.of());
        assertThat(summary.get("rawText")).asString().contains("そのダメージを受けない");
        assertThat(summary.get("executedEffects"))
            .asList()
            .singleElement()
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("effectType", "PREVENT_DAMAGE")
            .containsEntry("applied", true)
            .containsEntry("damageBefore", 120)
            .containsEntry("damageAfter", 0)
            .containsEntry("diceRoll", 1)
            .containsEntry("diceMatched", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldKeepDamageWhenDiceConditionFails() {
        when(
            jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("FROM match_holomems"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq(801L)
            )
        ).thenReturn(row(
            "id", 701L,
            "match_card_id", 801L,
            "zone", "CENTER",
            "current_level", "DEBUT"
        ));
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("m.passive_effect_json"), eq(100L), eq(20L)))
            .thenReturn(List.of(row(
                "holomem_id", 901L,
                "match_card_id", 902L,
                "card_id", "HBP01-027",
                "zone", "COLLAB",
                "current_level", "FIRST",
                "passive_text", "{\"キーワード\":\"ギフト：ターンに1回、自分のホロメンがダメージを受ける時、サイコロを1回振れる。奇数の時、そのダメージを受けない。\"}"
            )));
        when(diceService.rollD6()).thenReturn(2);

        Map<String, Object> summary = service.resolveTriggeredGiftDamagePrevention(
            100L,
            20L,
            10L,
            501L,
            801L,
            2,
            120
        );

        assertThat(summary)
            .containsEntry("damageAfter", 120)
            .containsEntry("applied", true)
            .containsEntry("preventedDamage", false)
            .containsEntry("diceRoll", 2)
            .containsEntry("diceMatched", false);
        assertThat(summary.get("skippedEffects"))
            .asList()
            .singleElement()
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("effectType", "PREVENT_DAMAGE")
            .containsEntry("applied", false)
            .containsEntry("damageBefore", 120)
            .containsEntry("damageAfter", 120)
            .containsEntry("diceRoll", 2)
            .containsEntry("diceMatched", false)
            .containsEntry("skipped", true)
            .containsEntry("reason", "條件未成立：骰子結果不符");
    }

    private Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            row.put((String) entries[i], entries[i + 1]);
        }
        return row;
    }
}
