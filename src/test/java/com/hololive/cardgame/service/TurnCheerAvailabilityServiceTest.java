package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class TurnCheerAvailabilityServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TurnCheerAvailabilityService service = new TurnCheerAvailabilityService(jdbcTemplate);

    @Test
    void findAvailabilityShouldReturnFirstValidSourceAndOrderedTargets() throws Exception {
        ResultSet sourceRs = mock(ResultSet.class);
        when(sourceRs.next()).thenReturn(true);
        when(sourceRs.getLong("card_instance_id")).thenReturn(500L);
        when(sourceRs.getString("card_id")).thenReturn("hY01-001");
        when(sourceRs.getString("zone")).thenReturn("CHEER_DECK");
        when(jdbcTemplate.query(
            contains("FROM match_cards mc"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(10L)
        )).thenAnswer(invocation -> {
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            return extractor.extractData(sourceRs);
        });
        when(jdbcTemplate.queryForList(
            contains("FROM match_holomems h"),
            eq(100L),
            eq(10L)
        )).thenReturn(List.of(Map.of(
            "card_instance_id", 900L,
            "card_id", "hBP01-001",
            "zone", "CENTER",
            "name", "Tokino Sora",
            "card_type", "HOLOMEM",
            "level_type", "DEBUT",
            "image_url", "center.png"
        )));

        TurnCheerAvailabilityService.TurnCheerAvailability availability = service
            .findAvailability(100L, 10L)
            .orElseThrow();

        assertThat(availability.sourceCardInstanceId()).isEqualTo(500L);
        assertThat(availability.sourceCardId()).isEqualTo("hY01-001");
        assertThat(availability.sourceZone()).isEqualTo("CHEER_DECK");
        assertThat(availability.targets()).containsExactly(new TurnCheerAvailabilityService.TurnCheerTarget(
            900L,
            "hBP01-001",
            "Tokino Sora",
            "HOLOMEM",
            "DEBUT",
            "CENTER",
            "center.png"
        ));
    }
}
