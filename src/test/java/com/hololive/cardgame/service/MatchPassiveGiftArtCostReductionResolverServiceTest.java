package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchPassiveGiftArtCostReductionResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchPassiveGiftArtCostReductionResolverService service =
        new MatchPassiveGiftArtCostReductionResolverService(
            jdbcTemplate,
            objectMapper,
            effectTextParser,
            new GiftTriggerMatcher(),
            new SearchCriteriaParser(jdbcTemplate, effectTextParser)
        );

    @Test
    void resolveFromHolderShouldApplySelfCostReductionOnlyToHolder() {
        Map<String, Integer> ownReduction = service.resolvePassiveGiftArtCostReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"このホロメンのアーツに必要な青-1\"}"),
            target(901L, "CENTER", "DEBUT", "Tokino Sora", "雨のマントラ", Set.of("#0期生"))
        );

        Map<String, Integer> otherReduction = service.resolvePassiveGiftArtCostReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"このホロメンのアーツに必要な青-1\"}"),
            target(902L, "CENTER", "DEBUT", "Roboco", "雨のマントラ", Set.of("#0期生"))
        );

        assertThat(ownReduction).containsEntry("BLUE", 1);
        assertThat(otherReduction).isEmpty();
    }

    @Test
    void resolveFromHolderShouldRespectTargetZoneLevelAndArtNameConditions() {
        String passiveJson = "{\"キーワード\":\"自分のDebutセンターホロメンのアーツ「雨のマントラ」に必要な無色-1\"}";

        Map<String, Integer> validReduction = service.resolvePassiveGiftArtCostReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", passiveJson),
            target(902L, "CENTER", "DEBUT", "Tokino Sora", "雨のマントラ", Set.of("#0期生"))
        );
        Map<String, Integer> wrongZoneReduction = service.resolvePassiveGiftArtCostReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", passiveJson),
            target(902L, "COLLAB", "DEBUT", "Tokino Sora", "雨のマントラ", Set.of("#0期生"))
        );
        Map<String, Integer> wrongArtReduction = service.resolvePassiveGiftArtCostReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", passiveJson),
            target(902L, "CENTER", "DEBUT", "Tokino Sora", "別のアーツ", Set.of("#0期生"))
        );

        assertThat(validReduction).containsEntry("COLORLESS", 1);
        assertThat(wrongZoneReduction).isEmpty();
        assertThat(wrongArtReduction).isEmpty();
    }

    @Test
    void resolveFromHolderShouldRespectHolderZoneRestriction() {
        Map<String, Integer> reduction = service.resolvePassiveGiftArtCostReductionFromHolder(
            100L,
            20L,
            holder(901L, "COLLAB", "{\"キーワード\":\"センターポジション限定：自分のホロメンのアーツに必要な青-1\"}"),
            target(902L, "CENTER", "DEBUT", "Tokino Sora", "雨のマントラ", Set.of("#0期生"))
        );

        assertThat(reduction).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveFromHolderShouldRespectReferencedSpOshiSkillHistory() {
        String passiveJson = "{\"キーワード\":\"SP推しスキル「人生リセットボタン」を使っていたなら、自分のホロメンのアーツに必要な無色-1\"}";
        when(
            jdbcTemplate.query(
                contains("FROM match_actions"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq("SP"),
                eq("SP"),
                eq("人生リセットボタン")
            )
        ).thenReturn(1);

        Map<String, Integer> reduction = service.resolvePassiveGiftArtCostReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", passiveJson),
            target(902L, "CENTER", "DEBUT", "Tokino Sora", "雨のマントラ", Set.of("#0期生"))
        );

        assertThat(reduction).containsEntry("COLORLESS", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldLoadTargetAndHoldersThenSumReductions() {
        when(
            jdbcTemplate.query(
                contains("COALESCE(c.tags_json"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq(902L)
            )
        ).thenReturn(target(902L, "CENTER", "DEBUT", "Tokino Sora", "雨のマントラ", Set.of("#0期生")));
        when(
            jdbcTemplate.query(
                contains("h.zone IN ('CENTER', 'COLLAB')"),
                any(RowMapper.class),
                eq(100L),
                eq(20L)
            )
        ).thenReturn(List.of(
            holder(901L, "CENTER", "{\"キーワード\":\"自分のDebutセンターホロメンのアーツに必要な無色-1\"}"),
            holder(903L, "COLLAB", "{\"キーワード\":\"センターポジション・コラボポジション限定：自分のホロメンのアーツに必要な青-1\"}")
        ));

        Map<String, Integer> reduction = service.resolvePassiveGiftArtCheerCostReduction(
            100L,
            20L,
            902L,
            "雨のマントラ"
        );

        assertThat(reduction).containsEntry("COLORLESS", 1);
        assertThat(reduction).containsEntry("BLUE", 1);
    }

    private MatchPassiveGiftArtCostReductionResolverService.PassiveGiftHolderContext holder(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {
        return new MatchPassiveGiftArtCostReductionResolverService.PassiveGiftHolderContext(
            holomemId,
            stageZone,
            passiveEffectJsonText
        );
    }

    private MatchPassiveGiftArtCostReductionResolverService.PassiveGiftArtCostReductionTargetContext target(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        String artName,
        Set<String> tags
    ) {
        return new MatchPassiveGiftArtCostReductionResolverService.PassiveGiftArtCostReductionTargetContext(
            holomemId,
            stageZone,
            levelType,
            cardName,
            artName,
            tags
        );
    }
}
