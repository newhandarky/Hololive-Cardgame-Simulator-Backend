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

class PlayCardActionResolverTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PlayCardActionResolver resolver = new PlayCardActionResolver(jdbcTemplate);

    @Test
    void resolveShouldMoveCardToStageAndCreateHolomemStack() {
        PlayCardAction action = action(false);
        PlayCardValidationContext context = validationContext();
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(false), eq(701L), eq(100L), eq(10L)))
            .thenReturn(1);
        when(
            jdbcTemplate.query(
                contains("INSERT INTO match_holomems"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(10L),
                eq(701L),
                eq("hBP01-001"),
                eq("BACK"),
                eq(false),
                eq("DEBUT"),
                eq(4)
            )
        ).thenReturn(901L);
        when(jdbcTemplate.update(contains("INSERT INTO match_holomem_stack_cards"), eq(901L), eq(701L)))
            .thenReturn(1);

        PlayCardResolutionResult result = resolver.resolve(action, context);

        assertThat(result.cardInstanceId()).isEqualTo(701L);
        assertThat(result.cardId()).isEqualTo("hBP01-001");
        assertThat(result.sourceZone()).isEqualTo("HAND");
        assertThat(result.targetZone()).isEqualTo("BACK");
        assertThat(result.matchHolomemId()).isEqualTo(901L);
        assertThat(result.enteredTurnNumber()).isEqualTo(4);
        assertThat(result.faceDown()).isFalse();
        assertThat(result.currentLevel()).isEqualTo("DEBUT");

        verify(jdbcTemplate).update(contains("UPDATE match_cards"), eq(false), eq(701L), eq(100L), eq(10L));
        verify(jdbcTemplate).update(contains("INSERT INTO match_holomem_stack_cards"), eq(901L), eq(701L));
    }

    private PlayCardAction action(boolean openingReset) {
        return new PlayCardAction(
            "PLAY_CARD",
            100L,
            10L,
            701L,
            "BACK",
            4,
            openingReset,
            PlayCardAction.ActionSource.TEST,
            "trace-play-card",
            "idem-play-card",
            LocalDateTime.now()
        );
    }

    private PlayCardValidationContext validationContext() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(4);
        return new PlayCardValidationContext(
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
            true,
            true,
            0,
            new PlayCardSourceCardSnapshot(701L, "hBP01-001", "HAND", true, "DEBUT")
        );
    }
}
