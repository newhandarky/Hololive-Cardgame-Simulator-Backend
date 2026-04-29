package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class FollowupTriggerConfirmPendingDecisionCreatorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final FollowupTriggerConfirmPendingDecisionCreator creator = new FollowupTriggerConfirmPendingDecisionCreator(
        new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, new ObjectMapper())
    );

    @Test
    void createShouldDelegateAdditionalContextBounds() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        whenInsertReturns(902L);

        FollowupInteractionDecision decision = creator.create(
            100L,
            10L,
            "BLOOM",
            701L,
            "hBP01-001",
            "BLOOM_EFFECT",
            "確認 Bloom 效果",
            "confirm bloom?",
            null,
            4,
            Map.of("minSelect", 1, "maxSelect", 2, "sourceLevelType", "DEBUT")
        );

        assertThat(decision).isEqualTo(new FollowupInteractionDecision(902L, "TRIGGER_EFFECT_CONFIRM"));
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
            "BLOOM",
            701L,
            "hBP01-001",
            "BLOOM_EFFECT",
            1,
            2,
            "PENDING",
            argsCaptor.getValue()[10]
        );
        assertThat((String) argsCaptor.getValue()[10])
            .contains("\"sourceActionType\":\"BLOOM\"")
            .contains("\"message\":\"confirm bloom?\"")
            .contains("\"cards\":[]")
            .contains("\"sourceLevelType\":\"DEBUT\"")
            .contains("\"turnNumber\":4");
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
