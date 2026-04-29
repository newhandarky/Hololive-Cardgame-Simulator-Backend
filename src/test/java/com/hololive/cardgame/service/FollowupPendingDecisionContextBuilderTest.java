package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FollowupPendingDecisionContextBuilderTest {

    private final FollowupPendingDecisionContextBuilder builder = new FollowupPendingDecisionContextBuilder();

    @Test
    void buildPendingDecisionContextShouldIncludeLookTopDeckFields() {
        Map<String, Object> card = Map.of("cardInstanceId", 101L, "cardId", "HBP99-001");
        Map<String, Object> context = builder.buildPendingDecisionContext(
            new FollowupInteractionContext(
                "LOOK_TOP_DECK",
                "查看牌庫頂",
                "選擇保留在牌庫頂的卡片；若不選擇則放到底部。",
                0,
                1,
                List.of(card),
                List.of(101L),
                List.of("TOP", "BOTTOM"),
                101L,
                "HBP99-001"
            ),
            "LOOK_TOP_DECK"
        );

        assertThat(context).containsEntry("interactionType", "LOOK_TOP_DECK");
        assertThat(context).containsEntry("title", "查看牌庫頂");
        assertThat(context).containsEntry("message", "選擇保留在牌庫頂的卡片；若不選擇則放到底部。");
        assertThat(context).containsEntry("cards", List.of(card));
        assertThat(context).containsEntry("placementOptions", List.of("TOP", "BOTTOM"));
        assertThat(context).containsEntry("effectType", "LOOK_TOP_DECK");
        assertThat(context).containsEntry("candidateCardInstanceIds", List.of(101L));
        assertThat(context).containsEntry("candidateCards", List.of(card));
        assertThat(context).containsEntry("lookedCardInstanceId", 101L);
        assertThat(context).containsEntry("lookedCardId", "HBP99-001");
    }

    @Test
    void buildPendingDecisionContextShouldOmitOptionalFieldsWhenEmpty() {
        Map<String, Object> context = builder.buildPendingDecisionContext(
            new FollowupInteractionContext(
                "LOOK_OPPONENT_HAND",
                "查看對手手牌",
                "以下為本次效果可查看的對手手牌。",
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                null,
                " "
            ),
            "LOOK_OPPONENT_HAND"
        );

        assertThat(context).doesNotContainKey("placementOptions");
        assertThat(context).doesNotContainKey("lookedCardInstanceId");
        assertThat(context).doesNotContainKey("lookedCardId");
        assertThat(context).containsEntry("candidateCardInstanceIds", List.of());
        assertThat(context).containsEntry("candidateCards", List.of());
    }
}
