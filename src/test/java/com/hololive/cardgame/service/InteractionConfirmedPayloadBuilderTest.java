package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InteractionConfirmedPayloadBuilderTest {

    private final InteractionConfirmedPayloadBuilder builder = new InteractionConfirmedPayloadBuilder();

    @Test
    void buildLookTopDeckPayloadShouldKeepPlacementFields() {
        Map<String, Object> payload = builder.buildLookTopDeckPayload(
            101L,
            "LOOK_TOP_DECK",
            "PLAY_SUPPORT",
            801L,
            false
        );

        assertThat(payload)
            .containsEntry("decisionId", 101L)
            .containsEntry("decisionType", "LOOK_TOP_DECK")
            .containsEntry("sourceActionType", "PLAY_SUPPORT")
            .containsEntry("lookedCardInstanceId", 801L)
            .containsEntry("placement", "BOTTOM");
    }

    @Test
    void buildLookZonePayloadShouldKeepLookedCardCount() {
        Map<String, Object> payload = builder.buildLookZonePayload(
            102L,
            "LOOK_OPPONENT_HAND",
            "USE_OSHI_SKILL",
            3
        );

        assertThat(payload)
            .containsEntry("decisionId", 102L)
            .containsEntry("decisionType", "LOOK_OPPONENT_HAND")
            .containsEntry("sourceActionType", "USE_OSHI_SKILL")
            .containsEntry("lookedCardCount", 3);
    }

    @Test
    void buildReorderDeckBottomPayloadShouldKeepOrderedCards() {
        Map<String, Object> payload = builder.buildReorderDeckBottomPayload(
            103L,
            "REORDER_DECK_BOTTOM",
            "PLAY_SUPPORT",
            List.of(901L, 902L)
        );

        assertThat(payload)
            .containsEntry("decisionId", 103L)
            .containsEntry("decisionType", "REORDER_DECK_BOTTOM")
            .containsEntry("sourceActionType", "PLAY_SUPPORT")
            .containsEntry("orderedCardInstanceIds", List.of(901L, 902L));
    }
}
