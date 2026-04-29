package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class FollowupInteractionPendingDecisionWriterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final FollowupInteractionPendingDecisionWriter writer = new FollowupInteractionPendingDecisionWriter(
        jdbcTemplate,
        new MatchPayloadJsonService(new ObjectMapper()),
        new FollowupPendingDecisionContextBuilder()
    );

    @Test
    void createShouldInsertFollowupInteractionPendingDecision() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        whenInsertReturns(701L);

        FollowupInteractionDecision decision = writer.create(
            100L,
            10L,
            "PLAY_SUPPORT",
            501L,
            "hBP01-001",
            "LOOK_TOP_DECK",
            new FollowupInteractionContext(
                "LOOK_TOP_DECK",
                "查看牌庫頂",
                "選擇保留在牌庫頂的卡片；若不選擇則放到底部。",
                0,
                1,
                List.of(Map.of("cardInstanceId", 801L, "cardId", "hBP02-001")),
                List.of(801L),
                List.of("TOP", "BOTTOM"),
                801L,
                "hBP02-001"
            )
        );

        assertThat(decision).isEqualTo(new FollowupInteractionDecision(701L, "LOOK_TOP_DECK"));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly(
            100L,
            10L,
            "LOOK_TOP_DECK",
            "PLAY_SUPPORT",
            501L,
            "hBP01-001",
            "LOOK_TOP_DECK",
            0,
            1,
            "PENDING",
            argsCaptor.getValue()[10]
        );
        assertThat((String) argsCaptor.getValue()[10])
            .contains("\"interactionType\":\"LOOK_TOP_DECK\"")
            .contains("\"placementOptions\":[\"TOP\",\"BOTTOM\"]")
            .contains("\"lookedCardInstanceId\":801");
    }

    @Test
    void createShouldRejectBlockingPendingDecision() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> writer.create(
            100L,
            10L,
            "PLAY_SUPPORT",
            501L,
            "hBP01-001",
            "LOOK_TOP_DECK",
            new FollowupInteractionContext(
                "LOOK_TOP_DECK",
                "查看牌庫頂",
                "message",
                0,
                1,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
            )
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("待處理的互動");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void whenInsertReturns(Long decisionId) throws Exception {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
            .thenAnswer(invocation -> {
                ResultSetExtractor extractor = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(decisionId != null);
                when(rs.getLong("id")).thenReturn(decisionId == null ? 0L : decisionId);
                return extractor.extractData(rs);
            });
    }
}
