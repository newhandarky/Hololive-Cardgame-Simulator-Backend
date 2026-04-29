package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FollowupDecisionPayloadAppenderTest {

    private final FollowupDecisionPayloadAppender appender = new FollowupDecisionPayloadAppender();

    @Test
    void appendShouldAddCommonPendingInteractionFields() {
        Map<String, Object> payload = new LinkedHashMap<>();

        appender.append(payload, new FollowupInteractionDecision(101L, "LOOK_OPPONENT_HAND"));

        assertThat(payload)
            .containsEntry("pendingInteractionDecisionId", 101L)
            .containsEntry("pendingInteractionDecisionType", "LOOK_OPPONENT_HAND")
            .doesNotContainKey("pendingLookTopDeckDecisionId");
    }

    @Test
    void appendShouldKeepLookTopDeckCompatibilityField() {
        Map<String, Object> payload = new LinkedHashMap<>();

        appender.append(payload, new FollowupInteractionDecision(202L, "LOOK_TOP_DECK"));

        assertThat(payload)
            .containsEntry("pendingInteractionDecisionId", 202L)
            .containsEntry("pendingInteractionDecisionType", "LOOK_TOP_DECK")
            .containsEntry("pendingLookTopDeckDecisionId", 202L);
    }

    @Test
    void appendShouldIgnoreNullInputs() {
        Map<String, Object> payload = new LinkedHashMap<>();

        appender.append(payload, null);
        appender.append(null, new FollowupInteractionDecision(303L, "LOOK_TOP_DECK"));
        appender.append(payload, new FollowupInteractionDecision(null, "LOOK_TOP_DECK"));

        assertThat(payload).isEmpty();
    }
}
