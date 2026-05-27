package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class PendingDecisionStoreTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PendingDecisionStore store = new PendingDecisionStore(jdbcTemplate, new ObjectMapper());

    @Test
    void loadForUpdateShouldMapPendingDecisionContext() throws Exception {
        ArgumentCaptor<ResultSetExtractor<PendingDecision>> extractorCaptor =
            ArgumentCaptor.forClass(ResultSetExtractor.class);
        when(jdbcTemplate.query(
            contains("FOR UPDATE"),
            extractorCaptor.capture(),
            eq(300L),
            eq(100L),
            eq(10L),
            eq(PendingDecisionReader.PENDING_STATUS)
        )).thenReturn(null);

        store.loadForUpdate(100L, 10L, 300L);

        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString("context_text")).thenReturn(
            """
            {
              "targetHolomemCardInstanceId": 9001,
              "targetType": "own_holomem",
              "effectJson": "{\\"type\\":\\"DRAW\\"}",
              "candidateCardInstanceIds": [101, "102", -1, "bad", 101],
              "limited": true
            }
            """
        );
        when(rs.getLong("id")).thenReturn(300L);
        when(rs.getString("decision_type")).thenReturn("card_selection");
        when(rs.getString("source_action_type")).thenReturn("play_support");
        when(rs.getLong("source_card_instance_id")).thenReturn(500L);
        when(rs.getString("source_card_id")).thenReturn("hBP01-001");
        when(rs.getString("effect_type")).thenReturn("draw");
        when(rs.getInt("min_select")).thenReturn(1);
        when(rs.getInt("max_select")).thenReturn(0);

        PendingDecision pending = extractorCaptor.getValue().extractData(rs);

        assertThat(pending.decisionId()).isEqualTo(300L);
        assertThat(pending.decisionType()).isEqualTo("CARD_SELECTION");
        assertThat(pending.sourceActionType()).isEqualTo("PLAY_SUPPORT");
        assertThat(pending.sourceCardInstanceId()).isEqualTo(500L);
        assertThat(pending.sourceCardId()).isEqualTo("hBP01-001");
        assertThat(pending.effectType()).isEqualTo("DRAW");
        assertThat(pending.minSelect()).isEqualTo(1);
        assertThat(pending.maxSelect()).isEqualTo(1);
        assertThat(pending.targetHolomemCardInstanceId()).isEqualTo(9001L);
        assertThat(pending.targetType()).isEqualTo("own_holomem");
        assertThat(pending.effectJson()).isEqualTo("{\"type\":\"DRAW\"}");
        assertThat(pending.candidateCardInstanceIds()).containsExactly(101L, 102L);
        assertThat(pending.limited()).isTrue();
        assertThat(pending.contextNode().path("limited").asBoolean()).isTrue();
    }

    @Test
    void markResolvedShouldUpdatePendingDecisionOrThrowWhenStale() {
        when(jdbcTemplate.update(
            contains("UPDATE match_pending_decisions"),
            eq(300L),
            eq(PendingDecisionReader.PENDING_STATUS)
        )).thenReturn(1);

        store.markResolved(300L);

        verify(jdbcTemplate).update(
            contains("resolved_at = CURRENT_TIMESTAMP"),
            eq(300L),
            eq(PendingDecisionReader.PENDING_STATUS)
        );

        when(jdbcTemplate.update(
            any(String.class),
            eq(301L),
            eq(PendingDecisionReader.PENDING_STATUS)
        )).thenReturn(0);

        assertThatThrownBy(() -> store.markResolved(301L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("決策已失效");
    }
}
