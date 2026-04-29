package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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

class EffectPostTriggerPendingServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectPostTriggerPendingService service = new EffectPostTriggerPendingService(
        jdbcTemplate,
        new EffectPostTriggerConfirmMessageBuilder(),
        new FollowupTriggerConfirmPendingDecisionCreator(
            new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, new ObjectMapper())
        )
    );

    @Test
    void createShouldReturnNullWhenNoDeferredDownEvent() {
        FollowupInteractionDecision decision = service.createEffectPostTriggerConfirmPendingInteractionIfNeeded(
            100L,
            10L,
            "PLAY_SUPPORT",
            701L,
            "hBP01-001",
            Map.of("executedEffects", List.of(Map.of("downEvent", Map.of("triggered", true, "deferred", false)))),
            4
        );

        assertThat(decision).isNull();
    }

    @Test
    void createShouldWriteEffectPostTriggerPendingForNestedDownEvent() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        whenInsertReturns(601L);

        FollowupInteractionDecision decision = service.createEffectPostTriggerConfirmPendingInteractionIfNeeded(
            100L,
            10L,
            "USE_OSHI_SKILL",
            701L,
            "hBP01-001",
            Map.of(
                "executedEffects",
                List.of(Map.of(
                    "effect",
                    "nested",
                    "downEvent",
                    Map.of(
                        "triggered",
                        true,
                        "deferred",
                        true,
                        "downedOwnerUserId",
                        20L,
                        "downedCardId",
                        "hBP02-001",
                        "downedStageZone",
                        "CENTER",
                        "turnNumber",
                        4,
                        "requestedLifeLoss",
                        1,
                        "rawText",
                        "down text"
                    )
                ))
            ),
            4
        );

        assertThat(decision).isEqualTo(new FollowupInteractionDecision(601L, "TRIGGER_EFFECT_CONFIRM"));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).query(anyString(), any(ResultSetExtractor.class), argsCaptor.capture());
        Object[] insertArgs = argsCaptor.getAllValues().get(argsCaptor.getAllValues().size() - 1);
        assertThat(insertArgs).containsExactly(
            100L,
            10L,
            "TRIGGER_EFFECT_CONFIRM",
            "EFFECT_POST_TRIGGER",
            701L,
            "hBP01-001",
            "DOWN_EVENT",
            0,
            0,
            "PENDING",
            insertArgs[10]
        );
        assertThat((String) insertArgs[10])
            .contains("\"originSourceActionType\":\"USE_OSHI_SKILL\"")
            .contains("\"downedOwnerUserId\":20")
            .contains("\"downedCardId\":\"hBP02-001\"")
            .contains("\"requestedLifeLoss\":1")
            .contains("\"zone\":\"OSHI\"");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void whenInsertReturns(Long decisionId) throws Exception {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
            .thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                if (sql != null && sql.trim().startsWith("SELECT")) {
                    return null;
                }
                ResultSetExtractor extractor = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(decisionId != null);
                when(rs.getLong("id")).thenReturn(decisionId == null ? 0L : decisionId);
                return extractor.extractData(rs);
            });
    }
}
