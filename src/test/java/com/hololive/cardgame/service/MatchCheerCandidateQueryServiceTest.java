package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchCheerCandidateQueryServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchEffectSearchService searchService =
        new MatchEffectSearchService(jdbcTemplate, new EffectTextParser(new ObjectMapper()));

    @Test
    void findCheerCardFromZoneShouldReturnFirstSearchCandidateWithNormalizedZone() {
        MatchCheerCandidateQueryService service = new MatchCheerCandidateQueryService(jdbcTemplate, searchService);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("id", 501L);
        candidate.put("card_id", "CHEER-Y");
        when(jdbcTemplate.query(
            anyString(),
            anyRowMapper(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        )).thenReturn(List.of(candidate));

        Map<String, Object> source = service.findCheerCardFromZone(
            1L,
            10L,
            "cheer_deck",
            SearchCriteria.empty()
        );

        assertThat(source)
            .containsEntry("id", 501L)
            .containsEntry("card_id", "CHEER-Y")
            .containsEntry("zone", "CHEER_DECK");
    }

    @Test
    void findCheerCardFromZoneShouldRejectUnsupportedZone() {
        JdbcTemplate localJdbcTemplate = mock(JdbcTemplate.class);
        MatchCheerCandidateQueryService service = new MatchCheerCandidateQueryService(
            localJdbcTemplate,
            new MatchEffectSearchService(localJdbcTemplate, new EffectTextParser(new ObjectMapper()))
        );

        Map<String, Object> source = service.findCheerCardFromZone(1L, 10L, "HAND", SearchCriteria.empty());

        assertThat(source).isNull();
        verifyNoInteractions(localJdbcTemplate);
    }

    @Test
    void findAttachableCheerCardShouldUseFallbackZonePriority() {
        MatchCheerCandidateQueryService service = new MatchCheerCandidateQueryService(jdbcTemplate, searchService);
        Map<String, Object> fallbackSource = new LinkedHashMap<>();
        fallbackSource.put("id", 503L);
        fallbackSource.put("card_id", "CHEER-F");
        fallbackSource.put("zone", "ARCHIVE");
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(fallbackSource);

        Map<String, Object> source = service.findAttachableCheerCard(1L, 10L);

        assertThat(source)
            .containsEntry("id", 503L)
            .containsEntry("card_id", "CHEER-F")
            .containsEntry("zone", "ARCHIVE");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Map<String, Object>> anyRowMapper() {
        return any(RowMapper.class);
    }
}
