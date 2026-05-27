package com.hololive.cardgame.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchTurnStartCollabReturnServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchTurnStartCollabReturnService service = new MatchTurnStartCollabReturnService(jdbcTemplate);

    @Test
    void returnCollabToBackAsRestedShouldDoNothingWhenPlayerHasNoCollab() {
        when(jdbcTemplate.queryForList(contains("zone = 'COLLAB'"), eq(100L), eq(10L))).thenReturn(List.of());

        service.returnCollabToBackAsRested(100L, 10L);

        verify(jdbcTemplate, never()).update(contains("SET zone = 'BACK'"), any(), any());
    }

    @Test
    void returnCollabToBackAsRestedShouldMoveCollabToBackRested() {
        when(jdbcTemplate.queryForList(contains("zone = 'COLLAB'"), eq(100L), eq(10L)))
            .thenReturn(List.of(Map.of("id", 501L, "card_id", "HBP01-001")));
        when(jdbcTemplate.query(contains("JOIN cards"), any(ResultSetExtractor.class), eq(100L), eq(10L)))
            .thenReturn("一般中心");

        service.returnCollabToBackAsRested(100L, 10L);

        verify(jdbcTemplate).update(contains("SET zone = 'BACK'"), eq(100L), eq(10L));
        verify(jdbcTemplate, never()).update(contains("SET is_rested = FALSE"), any(PreparedStatementSetter.class));
    }

    @Test
    void returnCollabToBackAsRestedShouldKeepMovedHbp03039UnrestedWhenCenterIsFuwawa() {
        when(jdbcTemplate.queryForList(contains("zone = 'COLLAB'"), eq(100L), eq(10L)))
            .thenReturn(List.of(Map.of("id", 501L, "card_id", "HBP03-039")));
        when(jdbcTemplate.query(contains("JOIN cards"), any(ResultSetExtractor.class), eq(100L), eq(10L)))
            .thenReturn("フワワ・アビスガード");

        service.returnCollabToBackAsRested(100L, 10L);

        verify(jdbcTemplate).update(contains("SET zone = 'BACK'"), eq(100L), eq(10L));
        verify(jdbcTemplate).update(contains("SET is_rested = FALSE"), any(PreparedStatementSetter.class));
    }
}
