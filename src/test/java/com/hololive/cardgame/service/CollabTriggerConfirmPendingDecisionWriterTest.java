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

class CollabTriggerConfirmPendingDecisionWriterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CollabTriggerConfirmPendingDecisionWriter writer = new CollabTriggerConfirmPendingDecisionWriter(
        jdbcTemplate,
        new ObjectMapper()
    );

    @Test
    void createTriggeredEffectConfirmPendingInteractionShouldInsertPendingDecision() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        whenInsertReturns(701L);

        CollabFollowupDecision decision = writer.createTriggeredEffectConfirmPendingInteraction(
            100L,
            10L,
            "collab",
            801L,
            "HBP99-001",
            "COLLAB_TRIGGER",
            "確認連動觸發效果",
            "confirm?",
            List.of(Map.of("cardInstanceId", 801L)),
            4,
            Map.of("minSelect", 1, "maxSelect", 2, "hasCollabEffect", true)
        );

        assertThat(decision).isEqualTo(new CollabFollowupDecision(701L, "TRIGGER_EFFECT_CONFIRM"));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
            anyString(),
            any(ResultSetExtractor.class),
            argsCaptor.capture()
        );
        assertThat(argsCaptor.getValue()).containsExactly(
            100L,
            10L,
            "TRIGGER_EFFECT_CONFIRM",
            "COLLAB",
            801L,
            "HBP99-001",
            "COLLAB_TRIGGER",
            1,
            2,
            "PENDING",
            argsCaptor.getValue()[10]
        );
        assertThat((String) argsCaptor.getValue()[10])
            .contains("\"interactionType\":\"TRIGGER_EFFECT_CONFIRM\"")
            .contains("\"sourceActionType\":\"COLLAB\"")
            .contains("\"title\":\"確認連動觸發效果\"")
            .contains("\"message\":\"confirm?\"")
            .contains("\"turnNumber\":4")
            .contains("\"hasCollabEffect\":true");
    }

    @Test
    void createTriggeredEffectConfirmPendingInteractionShouldRejectBlockingPendingDecision() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> writer.createTriggeredEffectConfirmPendingInteraction(
            100L,
            10L,
            "COLLAB",
            801L,
            "HBP99-001",
            "COLLAB_TRIGGER",
            "確認連動觸發效果",
            "confirm?",
            List.of(),
            4,
            Map.of()
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
