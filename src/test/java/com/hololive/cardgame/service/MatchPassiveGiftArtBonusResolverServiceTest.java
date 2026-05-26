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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchPassiveGiftArtBonusResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchPassiveGiftArtBonusResolverService service = new MatchPassiveGiftArtBonusResolverService(
        jdbcTemplate,
        objectMapper,
        effectTextParser,
        new GiftTriggerMatcher(),
        new SearchCriteriaParser(jdbcTemplate, effectTextParser)
    );

    @Test
    void resolveFromHolderShouldApplySelfArtBonusOnlyToHolder() {
        int ownBonus = service.resolvePassiveGiftArtBonusFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"このホロメンのアーツ+20\"}"),
            target(901L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"), Set.of()),
            "CENTER"
        );

        int otherBonus = service.resolvePassiveGiftArtBonusFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"このホロメンのアーツ+20\"}"),
            target(902L, "CENTER", "DEBUT", "Roboco", Set.of("#0期生"), Set.of()),
            "CENTER"
        );

        assertThat(ownBonus).isEqualTo(20);
        assertThat(otherBonus).isZero();
    }

    @Test
    void resolveFromHolderShouldRespectTargetZoneLevelAndTagConditions() {
        MatchPassiveGiftArtBonusResolverService.StaticArtBonusTargetContext validTarget =
            target(902L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"), Set.of());

        int validBonus = service.resolvePassiveGiftArtBonusFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"自分の#0期生 を持つDebutセンターホロメンのアーツ+30\"}"),
            validTarget,
            "CENTER"
        );
        int wrongZoneBonus = service.resolvePassiveGiftArtBonusFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"自分の#0期生 を持つDebutセンターホロメンのアーツ+30\"}"),
            target(902L, "COLLAB", "DEBUT", "Tokino Sora", Set.of("#0期生"), Set.of()),
            "CENTER"
        );
        int wrongLevelBonus = service.resolvePassiveGiftArtBonusFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"自分の#0期生 を持つDebutセンターホロメンのアーツ+30\"}"),
            target(902L, "CENTER", "FIRST", "Tokino Sora", Set.of("#0期生"), Set.of()),
            "CENTER"
        );

        assertThat(validBonus).isEqualTo(30);
        assertThat(wrongZoneBonus).isZero();
        assertThat(wrongLevelBonus).isZero();
    }

    @Test
    void resolveFromHolderShouldRespectHolderZoneRestriction() {
        int bonus = service.resolvePassiveGiftArtBonusFromHolder(
            100L,
            20L,
            holder(901L, "COLLAB", "{\"キーワード\":\"センターポジション限定：自分のホロメンのアーツ+20\"}"),
            target(902L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"), Set.of()),
            "CENTER"
        );

        assertThat(bonus).isZero();
    }

    @Test
    void resolveFromHolderShouldApplySpecialDamageBonusOnlyForMatchingTargetZone() {
        String passiveJson = "{\"キーワード\":\"自分の〈Tokino〉が相手のセンターホロメンに与える特殊ダメージ+20\"}";
        MatchPassiveGiftArtBonusResolverService.StaticArtBonusTargetContext target =
            target(902L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"), Set.of());

        int centerBonus = service.resolvePassiveGiftArtBonusFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", passiveJson),
            target,
            "CENTER"
        );
        int collabBonus = service.resolvePassiveGiftArtBonusFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", passiveJson),
            target,
            "COLLAB"
        );

        assertThat(centerBonus).isEqualTo(20);
        assertThat(collabBonus).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldLoadTargetAndHoldersThenSumArtBonuses() {
        when(
            jdbcTemplate.query(
                contains("tag.value AS tag"),
                any(RowMapper.class),
                eq(100L),
                eq(20L)
            )
        ).thenReturn(List.of());
        when(
            jdbcTemplate.query(
                contains("COALESCE(c.tags_json"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq(902L)
            )
        ).thenReturn(target(902L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"), Set.of()));
        when(
            jdbcTemplate.query(
                contains("h.zone IN ('CENTER', 'COLLAB')"),
                any(RowMapper.class),
                eq(100L),
                eq(20L)
            )
        ).thenReturn(List.of(
            holder(901L, "CENTER", "{\"キーワード\":\"自分の#0期生 を持つDebutセンターホロメンのアーツ+20\"}"),
            holder(903L, "COLLAB", "{\"キーワード\":\"センターポジション・コラボポジション限定：自分のホロメンのアーツ+10\"}")
        ));

        int bonus = service.resolvePassiveGiftArtBonus(100L, 20L, 902L, "CENTER");

        assertThat(bonus).isEqualTo(30);
    }

    private MatchPassiveGiftArtBonusResolverService.PassiveGiftHolderContext holder(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {
        return new MatchPassiveGiftArtBonusResolverService.PassiveGiftHolderContext(
            holomemId,
            stageZone,
            passiveEffectJsonText
        );
    }

    private MatchPassiveGiftArtBonusResolverService.StaticArtBonusTargetContext target(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags,
        Set<String> opponentStageTags
    ) {
        return new MatchPassiveGiftArtBonusResolverService.StaticArtBonusTargetContext(
            holomemId,
            stageZone,
            levelType,
            cardName,
            tags,
            opponentStageTags
        );
    }
}
