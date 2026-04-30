package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TriggerEffectConfirmPayloadBuilderTest {

    private final TriggerEffectConfirmPayloadBuilder builder = new TriggerEffectConfirmPayloadBuilder();

    @Test
    void buildBasePayloadShouldKeepConfirmFields() {
        Map<String, Object> payload = builder.buildBasePayload(101L, "PLAY_SUPPORT", true);

        assertThat(payload)
            .containsEntry("decisionId", 101L)
            .containsEntry("interactionType", "TRIGGER_EFFECT_CONFIRM")
            .containsEntry("sourceActionType", "PLAY_SUPPORT")
            .containsEntry("confirmed", true);
    }

    @Test
    void buildBasePayloadShouldKeepSkippedConfirmValue() {
        Map<String, Object> payload = builder.buildBasePayload(102L, "USE_OSHI_SKILL", false);

        assertThat(payload)
            .containsEntry("decisionId", 102L)
            .containsEntry("interactionType", "TRIGGER_EFFECT_CONFIRM")
            .containsEntry("sourceActionType", "USE_OSHI_SKILL")
            .containsEntry("confirmed", false);
    }
}
