package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
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

class BloomActionResolverTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final BloomActionResolver resolver = new BloomActionResolver(jdbcTemplate);

    @Test
    void resolveShouldMoveCardAppendStackAndUpdateTargetHolomem() {
        BloomAction action = action();
        BloomValidationContext context = validationContext();
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(701L), eq(100L), eq(10L))).thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(stack_order)"), eq(Integer.class), eq(901L)))
            .thenReturn(2);
        when(jdbcTemplate.update(contains("INSERT INTO match_holomem_stack_cards"), eq(901L), eq(701L), eq(2)))
            .thenReturn(1);
        when(
            jdbcTemplate.update(
                contains("UPDATE match_holomems"),
                eq(701L),
                eq("hBP01-002"),
                eq("FIRST"),
                eq(4),
                eq(901L),
                eq(100L),
                eq(10L)
            )
        ).thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM match_holomem_stack_cards"), eq(Integer.class), eq(901L)))
            .thenReturn(3);

        BloomResolutionResult result = resolver.resolve(action, context);

        assertThat(result.sourceCardInstanceId()).isEqualTo(701L);
        assertThat(result.sourceCardId()).isEqualTo("hBP01-002");
        assertThat(result.sourceLevelType()).isEqualTo("FIRST");
        assertThat(result.targetHolomemId()).isEqualTo(901L);
        assertThat(result.targetPreviousCardId()).isEqualTo("hBP01-001");
        assertThat(result.targetPreviousLevelType()).isEqualTo("DEBUT");
        assertThat(result.stackDepth()).isEqualTo(3);
        assertThat(result.bloomLevelOverrideApplied()).isFalse();

        verify(jdbcTemplate).update(contains("UPDATE match_cards"), eq(701L), eq(100L), eq(10L));
        verify(jdbcTemplate).update(contains("UPDATE match_holomems"), eq(701L), eq("hBP01-002"), eq("FIRST"), eq(4), eq(901L), eq(100L), eq(10L));
    }

    private BloomAction action() {
        return new BloomAction(
            "BLOOM",
            100L,
            10L,
            701L,
            801L,
            4,
            BloomAction.ActionSource.TEST,
            "trace-bloom",
            "idem-bloom",
            LocalDateTime.now()
        );
    }

    private BloomValidationContext validationContext() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(4);
        return new BloomValidationContext(
            match,
            10L,
            4,
            10L,
            MatchPhase.MAIN,
            "active",
            "STARTED",
            false,
            false,
            new BloomSourceCardSnapshot(701L, "hBP01-002", "Tokino Sora", "FIRST", 80, "HAND", true),
            new BloomTargetSnapshot(
                901L,
                801L,
                "hBP01-001",
                "Tokino Sora",
                "DEBUT",
                "CENTER",
                10,
                1,
                null,
                false,
                null,
                false
            )
        );
    }
}
