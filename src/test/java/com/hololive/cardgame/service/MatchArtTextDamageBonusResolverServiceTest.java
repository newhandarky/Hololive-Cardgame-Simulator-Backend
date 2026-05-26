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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchArtTextDamageBonusResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchArtTextDamageBonusResolverService service = new MatchArtTextDamageBonusResolverService(
        jdbcTemplate,
        objectMapper,
        effectTextParser,
        new GiftTriggerMatcher()
    );

    @Test
    void resolveFromRawTextShouldMultiplyCheerCountBonus() {
        int bonus = service.resolveArtTextDamageBonusFromRawText(
            100L,
            20L,
            2,
            "このホロメンのエール1枚につき、このアーツ+20。",
            target(901L, "CENTER", "SECOND", Set.of("#EN"), 3, 5, "OSHI")
        );

        assertThat(bonus).isEqualTo(60);
    }

    @Test
    void resolveFromRawTextShouldApplyLowLifeBonusOnlyAtThreeOrLess() {
        String rawText = "自分のライフが3以下の時、このアーツ+70。";

        int lowLifeBonus = service.resolveArtTextDamageBonusFromRawText(
            100L,
            20L,
            2,
            rawText,
            target(901L, "CENTER", "SECOND", Set.of("#EN"), 1, 3, "OSHI")
        );
        int highLifeBonus = service.resolveArtTextDamageBonusFromRawText(
            100L,
            20L,
            2,
            rawText,
            target(901L, "CENTER", "SECOND", Set.of("#EN"), 1, 4, "OSHI")
        );

        assertThat(lowLifeBonus).isEqualTo(70);
        assertThat(highLifeBonus).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveFromRawTextShouldApplyNamedHolomemArtHistoryBonus() {
        when(
            jdbcTemplate.query(
                contains("ma.action_type = 'ATTACK_ART'"),
                any(RowMapper.class),
                eq(100L),
                eq(20L),
                eq(2)
            )
        ).thenReturn(List.of("モココ・アビスガード"));

        int bonus = service.resolveArtTextDamageBonusFromRawText(
            100L,
            20L,
            2,
            "このターンに自分の〈モココ・アビスガード〉がアーツを使っていたなら、このアーツ+40。",
            target(901L, "CENTER", "SECOND", Set.of("#EN"), 1, 5, "OSHI")
        );

        assertThat(bonus).isEqualTo(40);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveFromRawTextShouldApplyOshiSkillHistoryBonus() {
        when(
            jdbcTemplate.query(
                contains("action_type = 'USE_OSHI_SKILL'"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq(2),
                eq("無限の体力")
            )
        ).thenReturn(1);

        int bonus = service.resolveArtTextDamageBonusFromRawText(
            100L,
            20L,
            2,
            "このターンに自分の推しスキル「無限の体力」を使っていたなら、このアーツ+40。",
            target(901L, "CENTER", "SECOND", Set.of("#EN"), 1, 5, "OSHI")
        );

        assertThat(bonus).isEqualTo(40);
    }

    @Test
    void resolveFromRawTextShouldApplyAttachedCheerThresholdAndOshiName() {
        String rawText = "自分の推しホロメンが〈ムーナ・ホシノヴァ〉で、このホロメンにエールが4枚以上付いているなら、このアーツ+60。";

        int matchingBonus = service.resolveArtTextDamageBonusFromRawText(
            100L,
            20L,
            2,
            rawText,
            target(901L, "CENTER", "SECOND", Set.of("#ID"), 4, 5, "ムーナ・ホシノヴァ")
        );
        int wrongOshiBonus = service.resolveArtTextDamageBonusFromRawText(
            100L,
            20L,
            2,
            rawText,
            target(901L, "CENTER", "SECOND", Set.of("#ID"), 4, 5, "別の推し")
        );

        assertThat(matchingBonus).isEqualTo(60);
        assertThat(wrongOshiBonus).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldLoadTargetAndParseArtEffectJson() {
        when(
            jdbcTemplate.query(
                contains("attached_cheer_count"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq(901L)
            )
        ).thenReturn(target(901L, "CENTER", "SECOND", Set.of("#EN"), 2, 5, "OSHI"));

        int bonus = service.resolveArtTextDamageBonus(
            100L,
            20L,
            2,
            901L,
            "{\"rawEffect\":\"このホロメンのエール1枚につき、このアーツ+20。\"}"
        );

        assertThat(bonus).isEqualTo(40);
    }

    private MatchArtTextDamageBonusResolverService.ArtSelfBonusTargetContext target(
        Long holomemId,
        String stageZone,
        String levelType,
        Set<String> tags,
        int attachedCheerCount,
        int currentLife,
        String oshiCardName
    ) {
        return new MatchArtTextDamageBonusResolverService.ArtSelfBonusTargetContext(
            holomemId,
            stageZone,
            levelType,
            tags,
            attachedCheerCount,
            currentLife,
            oshiCardName
        );
    }
}
