package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class CollabActionResolverTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CollabActionResolver resolver = new CollabActionResolver(jdbcTemplate);

    @Test
    void resolveShouldMoveHolomemToCollabAndTopDeckToHolopower() {
        CollabAction action = action();
        CollabValidationContext context = validationContext();
        when(jdbcTemplate.update(contains("UPDATE match_holomems"), eq(100L), eq(10L), eq(701L)))
            .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT id"), any(ResultSetExtractor.class), eq(100L), eq(10L)))
            .thenReturn(601L);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(order_index)"), eq(Integer.class), eq(100L), eq(10L)))
            .thenReturn(3);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(3), eq(601L), eq(100L), eq(10L)))
            .thenReturn(1);

        CollabResolutionResult result = resolver.resolve(action, context);

        assertThat(result.sourceHolomemId()).isEqualTo(901L);
        assertThat(result.sourceCardInstanceId()).isEqualTo(701L);
        assertThat(result.sourceCardId()).isEqualTo("hBP01-001");
        assertThat(result.sourceZone()).isEqualTo("BACK");
        assertThat(result.targetZone()).isEqualTo("COLLAB");
        assertThat(result.holopowerCardInstanceId()).isEqualTo(601L);

        verify(jdbcTemplate).update(contains("UPDATE match_holomems"), eq(100L), eq(10L), eq(701L));
        verify(jdbcTemplate).update(contains("UPDATE match_cards"), eq(3), eq(601L), eq(100L), eq(10L));
    }

    private CollabAction action() {
        return new CollabAction(
            "COLLAB",
            100L,
            10L,
            701L,
            "COLLAB",
            4,
            CollabAction.ActionSource.TEST,
            "trace-collab",
            "idem-collab",
            LocalDateTime.now()
        );
    }

    private CollabValidationContext validationContext() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(4);
        return new CollabValidationContext(
            match,
            10L,
            4,
            10L,
            MatchPhase.MAIN,
            "active",
            "STARTED",
            false,
            false,
            false,
            false,
            0,
            new CollabSourceHolomemSnapshot(901L, 701L, "hBP01-001", "BACK", false)
        );
    }
}
