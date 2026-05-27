package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchAddCheerSourceResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final SearchCriteriaParser searchCriteriaParser = new SearchCriteriaParser(jdbcTemplate, effectTextParser);

    @Test
    void resolveShouldUseArchiveWhenTextMentionsArchiveCheer() {
        List<String> requestedZones = new ArrayList<>();
        MatchAddCheerSourceResolverService service = newService(
            (matchId, userId, zone, criteria) -> {
                requestedZones.add(zone);
                assertThat(criteria.color()).isEqualTo("YELLOW");
                return Map.of("id", 501L, "card_id", "CHEER-Y", "zone", zone);
            }
        );

        Map<String, Object> source = service.resolvePreferredAddCheerSource(
            1L,
            10L,
            "自分のアーカイブの黄エール1枚を自分の〈虎金妃笑虎〉に送る。"
        );

        assertThat(source).containsEntry("id", 501L).containsEntry("zone", "ARCHIVE");
        assertThat(requestedZones).containsExactly("ARCHIVE");
    }

    @Test
    void resolveShouldUseCheerDeckWhenTextMentionsCheerDeck() {
        List<String> requestedZones = new ArrayList<>();
        MatchAddCheerSourceResolverService service = newService(
            (matchId, userId, zone, criteria) -> {
                requestedZones.add(zone);
                return Map.of("id", 502L, "card_id", "CHEER-D", "zone", zone);
            }
        );

        Map<String, Object> source = service.resolvePreferredAddCheerSource(
            1L,
            10L,
            "自分のエールデッキの上から1枚を、このホロメンに付ける。"
        );

        assertThat(source).containsEntry("id", 502L).containsEntry("zone", "CHEER_DECK");
        assertThat(requestedZones).containsExactly("CHEER_DECK");
    }

    @Test
    void resolveShouldFallbackWhenTextDoesNotMentionKnownSourceZone() {
        MatchAddCheerSourceResolverService service = newService(
            (matchId, userId, zone, criteria) -> {
                throw new AssertionError("zone finder should not be used");
            }
        );
        Map<String, Object> fallbackSource = new LinkedHashMap<>();
        fallbackSource.put("id", 503L);
        fallbackSource.put("card_id", "CHEER-F");
        fallbackSource.put("zone", "HAND");
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any())).thenReturn(fallbackSource);

        Map<String, Object> source = service.resolvePreferredAddCheerSource(
            1L,
            10L,
            "エール1枚を、このホロメンに付ける。"
        );

        assertThat(source).containsEntry("id", 503L).containsEntry("zone", "HAND");
    }

    private MatchAddCheerSourceResolverService newService(
        MatchAddCheerSourceResolverService.CheerZoneFinder cheerZoneFinder
    ) {
        return new MatchAddCheerSourceResolverService(jdbcTemplate, searchCriteriaParser, cheerZoneFinder);
    }
}
