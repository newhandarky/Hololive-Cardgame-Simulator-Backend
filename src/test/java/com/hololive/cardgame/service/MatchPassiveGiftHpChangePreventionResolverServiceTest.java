package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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

class MatchPassiveGiftHpChangePreventionResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchPassiveGiftHpChangePreventionResolverService service =
        new MatchPassiveGiftHpChangePreventionResolverService(
            jdbcTemplate,
            objectMapper,
            effectTextParser,
            new GiftTriggerMatcher(),
            new SearchCriteriaParser(jdbcTemplate, effectTextParser)
        );

    @Test
    void blocksFromHolderShouldApplySelfHpProtectionOnlyToHolder() {
        MatchPassiveGiftHpChangePreventionResolverService.PassiveGiftHolderContext holder = holder(
            901L,
            "CENTER",
            "{\"キーワード\":\"相手のメインステップの間、このホロメンのHPは相手の能力で減らず、変動しない。\"}"
        );

        assertThat(service.blocksOpponentAbilityHpChangeFromHolder(
            holder,
            target(901L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"))
        )).isTrue();
        assertThat(service.blocksOpponentAbilityHpChangeFromHolder(
            holder,
            target(902L, "COLLAB", "DEBUT", "Roboco", Set.of("#0期生"))
        )).isFalse();
    }

    @Test
    void blocksFromHolderShouldRespectTargetZoneAndLevelCriteria() {
        MatchPassiveGiftHpChangePreventionResolverService.PassiveGiftHolderContext holder = holder(
            901L,
            "CENTER",
            "{\"キーワード\":\"相手のメインステップの間、自分のDebutセンターホロメンのHPは相手の能力で減らず、変動しない。\"}"
        );

        assertThat(service.blocksOpponentAbilityHpChangeFromHolder(
            holder,
            target(902L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"))
        )).isTrue();
        assertThat(service.blocksOpponentAbilityHpChangeFromHolder(
            holder,
            target(902L, "COLLAB", "DEBUT", "Tokino Sora", Set.of("#0期生"))
        )).isFalse();
        assertThat(service.blocksOpponentAbilityHpChangeFromHolder(
            holder,
            target(902L, "CENTER", "FIRST", "Tokino Sora", Set.of("#0期生"))
        )).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void isBlockedShouldLoadTurnTargetAndHoldersThenReturnTrueWhenAnyHolderBlocks() {
        when(
            jdbcTemplate.query(
                contains("SELECT current_phase"),
                any(ResultSetExtractor.class),
                eq(100L)
            )
        ).thenReturn(new MatchPassiveGiftHpChangePreventionResolverService.MatchTurnContext("MAIN", 10L));
        when(
            jdbcTemplate.query(
                contains("COALESCE(c.tags_json"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq(902L)
            )
        ).thenReturn(target(902L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生")));
        when(
            jdbcTemplate.query(
                contains("h.zone IN ('CENTER', 'COLLAB')"),
                any(RowMapper.class),
                eq(100L),
                eq(20L)
            )
        ).thenReturn(List.of(
            holder(901L, "CENTER", "{\"キーワード\":\"このホロメンのアーツ+10\"}"),
            holder(903L, "COLLAB", "{\"キーワード\":\"相手のメインステップの間、自分のセンターホロメンのHPは相手の能力で減らず、変動しない。\"}")
        ));

        boolean blocked = service.isHpChangeBlockedByOpponentAbility(100L, 10L, 20L, 902L, "HEAL");

        assertThat(blocked).isTrue();
    }

    @Test
    void isBlockedShouldSkipInvalidSameOwnerAndArtDamageWithoutDb() {
        assertThat(service.isHpChangeBlockedByOpponentAbility(null, 10L, 20L, 902L, "HEAL")).isFalse();
        assertThat(service.isHpChangeBlockedByOpponentAbility(100L, 10L, 10L, 902L, "HEAL")).isFalse();
        assertThat(service.isHpChangeBlockedByOpponentAbility(100L, 10L, 20L, 902L, "ART_DAMAGE")).isFalse();

        verifyNoInteractions(jdbcTemplate);
    }

    private MatchPassiveGiftHpChangePreventionResolverService.PassiveGiftHolderContext holder(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {
        return new MatchPassiveGiftHpChangePreventionResolverService.PassiveGiftHolderContext(
            holomemId,
            stageZone,
            passiveEffectJsonText
        );
    }

    private MatchPassiveGiftHpChangePreventionResolverService.PassiveGiftHpChangePreventionTargetContext target(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags
    ) {
        return new MatchPassiveGiftHpChangePreventionResolverService.PassiveGiftHpChangePreventionTargetContext(
            holomemId,
            stageZone,
            levelType,
            cardName,
            tags
        );
    }
}
