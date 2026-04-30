package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SendCheerInteractionPayloadBuilderTest {

    private final SendCheerInteractionPayloadBuilder builder = new SendCheerInteractionPayloadBuilder();

    @Test
    void buildInteractionConfirmedPayloadShouldKeepSendCheerFields() {
        Map<String, Object> payload = builder.buildInteractionConfirmedPayload(
            101L,
            "TURN_CHEER",
            701L,
            "hY01-001",
            801L
        );

        assertThat(payload)
            .containsEntry("decisionId", 101L)
            .containsEntry("interactionType", "SEND_CHEER")
            .containsEntry("sourceActionType", "TURN_CHEER")
            .containsEntry("sourceCardInstanceId", 701L)
            .containsEntry("sourceCardId", "hY01-001")
            .containsEntry("targetHolomemCardInstanceId", 801L);
    }

    @Test
    void buildTurnCheerActionPayloadShouldKeepCheerActionFields() {
        Map<String, Object> payload = builder.buildTurnCheerActionPayload(
            702L,
            "hY01-002",
            802L
        );

        assertThat(payload)
            .containsEntry("sourceCardInstanceId", 702L)
            .containsEntry("sourceCardId", "hY01-002")
            .containsEntry("targetHolomemCardInstanceId", 802L)
            .doesNotContainKeys("decisionId", "interactionType", "sourceActionType");
    }
}
