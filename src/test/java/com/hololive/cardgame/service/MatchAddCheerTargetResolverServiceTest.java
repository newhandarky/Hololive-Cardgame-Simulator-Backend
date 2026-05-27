package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchAddCheerTargetResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final SearchCriteriaParser searchCriteriaParser = new SearchCriteriaParser(jdbcTemplate, effectTextParser);

    @Test
    void resolveShouldUseDefaultTargetResolverWhenTextHasNoRestrictions() {
        MatchAddCheerTargetResolverService service = newService((matchId, userId, targetType, requestedCardId, defaultOpponent) -> 22L);

        Long target = service.resolvePreferredAddCheerTargetHolomemId(
            1L,
            10L,
            "SELF",
            100L,
            "自分のエールデッキの上から1枚を、このホロメンに付ける。",
            false,
            null
        );

        assertThat(target).isEqualTo(22L);
        verify(jdbcTemplate, never()).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    void resolveShouldFallbackToOwnedStageWhenAnyOwnHolomemTextHasNoResolvedTarget() {
        MatchAddCheerTargetResolverService service = newService((matchId, userId, targetType, requestedCardId, defaultOpponent) -> null);
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenReturn(33L);

        Long target = service.resolvePreferredAddCheerTargetHolomemId(
            1L,
            10L,
            "SELF",
            null,
            "自分のエールデッキの上から1枚を、自分のホロメンに送る。",
            false,
            null
        );

        assertThat(target).isEqualTo(33L);
    }

    @Test
    void resolveShouldUseBackRestrictionWhenTextPrefersBackHolomem() {
        MatchAddCheerTargetResolverService service = newService((matchId, userId, targetType, requestedCardId, defaultOpponent) -> 22L);
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenReturn(44L);

        Long target = service.resolvePreferredAddCheerTargetHolomemId(
            1L,
            10L,
            "SELF",
            100L,
            "自分のエールデッキの上から1枚を、自分のバックホロメンに送る。",
            false,
            null
        );

        assertThat(target).isEqualTo(44L);
    }

    @Test
    void resolveShouldNotFallbackWhenRestrictedTargetIsMissing() {
        MatchAddCheerTargetResolverService service = newService((matchId, userId, targetType, requestedCardId, defaultOpponent) -> 22L);
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenReturn(null);

        Long target = service.resolvePreferredAddCheerTargetHolomemId(
            1L,
            10L,
            "SELF",
            100L,
            "自分のエールデッキの上から1枚を、自分のバックホロメンに送る。",
            false,
            null
        );

        assertThat(target).isNull();
    }

    private MatchAddCheerTargetResolverService newService(MatchAddCheerTargetResolverService.EffectTargetResolver targetResolver) {
        return new MatchAddCheerTargetResolverService(jdbcTemplate, searchCriteriaParser, targetResolver);
    }
}
