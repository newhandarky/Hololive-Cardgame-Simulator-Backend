package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class AttackPerformanceStateUpdaterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AttackPerformanceAvailabilityService attackPerformanceAvailabilityService =
        mock(AttackPerformanceAvailabilityService.class);
    private final MatchTimestampService matchTimestampService = mock(MatchTimestampService.class);
    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final AttackPerformanceStateUpdater updater = new AttackPerformanceStateUpdater(
        jdbcTemplate,
        attackPerformanceAvailabilityService,
        matchTimestampService,
        matchRepository
    );

    @Test
    void restAttackerAndSavePerformancePhaseShouldRestAttackerBeforeTouchAndSave() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        when(jdbcTemplate.update(any(String.class), any(), any(), any())).thenReturn(1);
        when(attackPerformanceAvailabilityService.hasAvailableArtAttacker(100L, 10L, 3)).thenReturn(true);

        boolean hasNextAction = updater.restAttackerAndSavePerformancePhase(context(match));

        assertThat(hasNextAction).isTrue();
        assertThat(match.getCurrentPhase()).isEqualTo(MatchPhase.PERFORMANCE.name());
        InOrder order = inOrder(
            jdbcTemplate,
            attackPerformanceAvailabilityService,
            matchTimestampService,
            matchRepository
        );
        order.verify(jdbcTemplate).update(
            contains("UPDATE match_holomems"),
            eq(501L),
            eq(100L),
            eq(10L)
        );
        order.verify(attackPerformanceAvailabilityService).hasAvailableArtAttacker(100L, 10L, 3);
        order.verify(matchTimestampService).touchUpdatedAt(match);
        order.verify(matchRepository).saveAndFlush(match);
    }

    @Test
    void restAttackerAndSavePerformancePhaseShouldRejectStaleRestUpdate() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        when(jdbcTemplate.update(any(String.class), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> updater.restAttackerAndSavePerformancePhase(context(match)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("藝能結算失敗");

        verify(attackPerformanceAvailabilityService, never()).hasAvailableArtAttacker(any(), any(), anyInt());
        verify(matchTimestampService, never()).touchUpdatedAt(any());
        verify(matchRepository, never()).saveAndFlush(any());
    }

    private AttackArtApplicationContext context(MatchEntity match) {
        return AttackArtApplicationContext.attackArt(
            match,
            100L,
            10L,
            20L,
            3,
            3001L,
            4001L,
            501L,
            "CENTER",
            "HBP01-087",
            "SECOND",
            "BLUE",
            "雨のマントラ",
            1,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":50}",
            null,
            Map.of(),
            List.of()
        );
    }
}
