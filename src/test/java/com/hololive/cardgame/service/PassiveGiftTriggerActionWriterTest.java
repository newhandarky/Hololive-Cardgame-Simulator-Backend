package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class PassiveGiftTriggerActionWriterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PassiveGiftTriggerActionWriter writer = new PassiveGiftTriggerActionWriter(
        jdbcTemplate,
        objectMapper,
        new EffectTextParser(objectMapper)
    );

    @Test
    void appendIncomingDamageReductionTriggerShouldSkipInvalidInput() {
        writer.appendIncomingDamageReductionTrigger(null, 201L, 3, 301L, "gift", 1);
        writer.appendIncomingDamageReductionTrigger(100L, null, 3, 301L, "gift", 1);
        writer.appendIncomingDamageReductionTrigger(100L, 201L, 0, 301L, "gift", 1);
        writer.appendIncomingDamageReductionTrigger(100L, 201L, 3, null, "gift", 1);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void appendIncomingDamageReductionTriggerShouldWritePayloadAndNextActionOrder() throws Exception {
        when(jdbcTemplate.query(
            anyString(),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(3)
        )).thenReturn(4);

        writer.appendIncomingDamageReductionTrigger(
            100L,
            201L,
            3,
            301L,
            null,
            1
        );

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
            contains("INSERT INTO match_actions"),
            eq(100L),
            eq(201L),
            eq(3),
            eq(5),
            payloadCaptor.capture()
        );

        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.get("triggerType").asText())
            .isEqualTo(PassiveGiftTriggerActionWriter.TRIGGER_TYPE_PASSIVE_INCOMING_DAMAGE_REDUCTION);
        assertThat(payload.get("giftHolderHolomemId").asLong()).isEqualTo(301L);
        assertThat(payload.get("giftText").asText()).isEmpty();
        assertThat(payload.get("diceRoll").asInt()).isEqualTo(1);
    }
}
