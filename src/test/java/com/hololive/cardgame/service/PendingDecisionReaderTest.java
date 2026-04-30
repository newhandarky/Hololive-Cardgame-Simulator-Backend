package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PendingDecisionReaderTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PendingDecisionReader reader = new PendingDecisionReader(jdbcTemplate);

    @Test
    void hasBlockingPendingDecisionShouldFilterByMatchUserAndPendingStatus() {
        when(jdbcTemplate.queryForObject(
            contains("FROM match_pending_decisions"),
            eq(Integer.class),
            eq(100L),
            eq(10L),
            eq(PendingDecisionReader.PENDING_STATUS)
        )).thenReturn(1);

        assertThat(reader.hasBlockingPendingDecision(100L, 10L)).isTrue();

        verify(jdbcTemplate).queryForObject(
            contains("AND user_id = ?"),
            eq(Integer.class),
            eq(100L),
            eq(10L),
            eq(PendingDecisionReader.PENDING_STATUS)
        );
    }

    @Test
    void hasAnyPendingDecisionShouldFilterByMatchAndPendingStatus() {
        when(jdbcTemplate.queryForObject(
            contains("FROM match_pending_decisions"),
            eq(Integer.class),
            eq(100L),
            eq(PendingDecisionReader.PENDING_STATUS)
        )).thenReturn(2);

        assertThat(reader.hasAnyPendingDecision(100L)).isTrue();

        verify(jdbcTemplate).queryForObject(
            contains("WHERE match_id = ?"),
            eq(Integer.class),
            eq(100L),
            eq(PendingDecisionReader.PENDING_STATUS)
        );
    }

    @Test
    void hasAnyPendingDecisionForUserShouldReturnFalseWhenCountIsNullOrZero() {
        when(jdbcTemplate.queryForObject(
            contains("FROM match_pending_decisions"),
            eq(Integer.class),
            eq(100L),
            eq(10L),
            eq(PendingDecisionReader.PENDING_STATUS)
        )).thenReturn(null);

        assertThat(reader.hasAnyPendingDecision(100L, 10L)).isFalse();

        when(jdbcTemplate.queryForObject(
            contains("FROM match_pending_decisions"),
            eq(Integer.class),
            eq(100L),
            eq(10L),
            eq(PendingDecisionReader.PENDING_STATUS)
        )).thenReturn(0);

        assertThat(reader.hasAnyPendingDecision(100L, 10L)).isFalse();
    }
}
