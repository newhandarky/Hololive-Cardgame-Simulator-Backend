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

class GiftPendingDecisionCreatorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final GiftPendingDecisionCreator creator = new GiftPendingDecisionCreator(
        new GiftTriggerInteractionCardsBuilder(jdbcTemplate),
        new GiftTriggeredEffectConfirmPendingInputBuilder(),
        new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, new ObjectMapper())
    );

    @Test
    void createWithCardsShouldWriteGiftPendingDecision() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        whenInsertReturns(501L);
        Map<String, Object> sourceCard = Map.of("cardInstanceId", 701L, "cardId", "hBP01-001", "zone", "CENTER");

        FollowupInteractionDecision decision = creator.createWithCards(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(sourceCard),
            List.of(giftTrigger("STAGE_ENTER", 801L, "hBP06-014")),
            4
        );

        assertThat(decision).isEqualTo(new FollowupInteractionDecision(501L, "TRIGGER_EFFECT_CONFIRM"));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).contains(
            "GIFT",
            701L,
            "hBP01-001",
            "GIFT_TRIGGER"
        );
        assertThat((String) argsCaptor.getValue()[10])
            .contains("\"sourceActionType\":\"GIFT\"")
            .contains("\"cards\":[")
            .contains("\"giftCount\":1");
    }

    @Test
    void createWithGiftTriggerInteractionCardsShouldKeepSourceCardContext() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        whenInsertReturns(502L);

        FollowupInteractionDecision decision = creator.createWithGiftTriggerInteractionCards(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(giftTrigger("BATON_TOUCH_BACK", 701L, "hBP01-001")),
            4
        );

        assertThat(decision).isEqualTo(new FollowupInteractionDecision(502L, "TRIGGER_EFFECT_CONFIRM"));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).query(anyString(), any(ResultSetExtractor.class), argsCaptor.capture());
        Object[] insertArgs = argsCaptor.getAllValues().get(argsCaptor.getAllValues().size() - 1);
        assertThat(insertArgs).contains(
            "GIFT",
            701L,
            "hBP01-001",
            "GIFT_TRIGGER"
        );
        assertThat((String) insertArgs[10])
            .contains("\"sourceActionType\":\"GIFT\"")
            .contains("\"cardInstanceId\":701")
            .contains("\"cardId\":\"hBP01-001\"")
            .contains("\"zone\":\"STAGE\"")
            .contains("\"triggerType\":\"BATON_TOUCH_BACK\"")
            .contains("\"giftHolderCardInstanceId\":701")
            .contains("\"giftCount\":1");
    }

    @Test
    void createWithGiftTriggerInteractionCardsShouldReturnNullWhenNoGiftEffects() {
        FollowupInteractionDecision decision = creator.createWithGiftTriggerInteractionCards(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(),
            4
        );

        assertThat(decision).isNull();
    }

    private Map<String, Object> giftTrigger(String triggerType, Long holderCardInstanceId, String holderCardId) {
        return Map.of(
            "triggerType",
            triggerType,
            "giftHolderCardInstanceId",
            holderCardInstanceId,
            "giftHolderCardId",
            holderCardId,
            "giftHolderZone",
            "BACK",
            "requestedEffects",
            List.of("DRAW"),
            "rawText",
            "gift text"
        );
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
