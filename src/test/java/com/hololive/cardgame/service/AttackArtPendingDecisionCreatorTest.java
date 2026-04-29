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

class AttackArtPendingDecisionCreatorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AttackArtPendingDecisionCreator creator = new AttackArtPendingDecisionCreator(
        new GiftTriggerInteractionCardsBuilder(jdbcTemplate),
        new AttackArtPostTriggerConfirmPendingInputBuilder(),
        new GiftTriggeredEffectConfirmPendingInputBuilder(),
        new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, new ObjectMapper()),
        new AttackPendingDecisionConversionService()
    );

    @Test
    void createAttackPostTriggerPendingShouldWriteAttackPostTriggerDecision() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any())).thenReturn(null);
        whenInsertReturns(301L);
        AttackPostTriggerPendingContext context = context(
            List.of(giftTrigger("ART_USED", 801L, "hBP06-014")),
            Map.of("downedCardId", "hBP02-001", "requestedLifeLoss", 1),
            List.of()
        );

        AttackPendingDecision decision = creator.createAttackPostTriggerPending(context, Map.of());

        assertThat(decision).isEqualTo(new AttackPendingDecision(301L, "TRIGGER_EFFECT_CONFIRM"));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).query(anyString(), any(ResultSetExtractor.class), argsCaptor.capture());
        Object[] insertArgs = argsCaptor.getAllValues().get(argsCaptor.getAllValues().size() - 1);
        assertThat(insertArgs).contains(
            "ATTACK_ART_POST_TRIGGER",
            "ATTACK_ART_POST_TRIGGER"
        );
        assertThat((String) insertArgs[10])
            .contains("\"sourceActionType\":\"ATTACK_ART_POST_TRIGGER\"")
            .contains("\"giftTriggers\"")
            .contains("\"downEvent\"")
            .contains("\"triggerSections\"");
    }

    @Test
    void createDefenderGiftPendingShouldWriteGiftDecisionForDefender() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any())).thenReturn(null);
        whenInsertReturns(401L);
        AttackPostTriggerPendingContext context = context(
            List.of(),
            null,
            List.of(giftTrigger("SELF_DOWNED", 901L, "hBP06-015"))
        );

        AttackPendingDecision decision = creator.createDefenderGiftPending(context, Map.of());

        assertThat(decision).isEqualTo(new AttackPendingDecision(401L, "TRIGGER_EFFECT_CONFIRM"));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).query(anyString(), any(ResultSetExtractor.class), argsCaptor.capture());
        Object[] insertArgs = argsCaptor.getAllValues().get(argsCaptor.getAllValues().size() - 1);
        assertThat(insertArgs).contains(
            "GIFT",
            "GIFT_TRIGGER"
        );
        assertThat((String) insertArgs[10])
            .contains("\"sourceActionType\":\"GIFT\"")
            .contains("\"giftTriggers\"")
            .contains("\"giftCount\":1");
    }

    private AttackPostTriggerPendingContext context(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview,
        List<Map<String, Object>> defenderGiftTriggeredEffects
    ) {
        return AttackPostTriggerPendingContext.attackArt(
            100L,
            10L,
            20L,
            4,
            701L,
            "hBP01-001",
            801L,
            "hBP02-001",
            giftTriggeredEffects,
            downEventPreview,
            defenderGiftTriggeredEffects
        );
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
                ResultSetExtractor extractor = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(decisionId != null);
                when(rs.getLong("id")).thenReturn(decisionId == null ? 0L : decisionId);
                return extractor.extractData(rs);
            });
    }
}
