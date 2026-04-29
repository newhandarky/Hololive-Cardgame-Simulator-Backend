package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GiftSelectionPendingContextBuilderTest {

    private final GiftSelectionPendingContextBuilder builder = new GiftSelectionPendingContextBuilder();

    @Test
    void buildSelectionPendingContextShouldReturnEmptyWhenNotExactlyOneSelectableTrigger() {
        assertThat(builder.buildSelectionPendingContext(null)).isEmpty();
        assertThat(builder.buildSelectionPendingContext(List.of())).isEmpty();
        assertThat(builder.buildSelectionPendingContext(List.of(Map.of("selectionRequired", false)))).isEmpty();
        assertThat(builder.buildSelectionPendingContext(
            List.of(
                Map.of("selectionRequired", true, "selectionCandidateCardInstanceIds", List.of(1)),
                Map.of("selectionRequired", true, "selectionCandidateCardInstanceIds", List.of(2))
            )
        )).isEmpty();
    }

    @Test
    void buildSelectionPendingContextShouldReturnSelectionFields() {
        Map<String, Object> context = builder.buildSelectionPendingContext(
            List.of(
                Map.of(
                    "selectionRequired",
                    "true",
                    "selectionCandidateCardInstanceIds",
                    List.of("101", 102, 102, 0, "bad"),
                    "giftHolderCardInstanceId",
                    "201",
                    "selectionMinSelect",
                    "1",
                    "selectionMaxSelect",
                    2
                )
            )
        );

        assertThat(context).containsEntry("candidateCardInstanceIds", List.of(101L, 102L));
        assertThat(context).containsEntry("selectionGiftHolderCardInstanceId", 201L);
        assertThat(context).containsEntry("minSelect", 1);
        assertThat(context).containsEntry("maxSelect", 2);
    }

    @Test
    void buildSelectionPendingContextShouldUseMinAsFloorForMax() {
        Map<String, Object> context = builder.buildSelectionPendingContext(
            List.of(
                Map.of(
                    "selectionRequired",
                    true,
                    "selectionCandidateCardInstanceIds",
                    List.of(101),
                    "selectionMinSelect",
                    "3",
                    "selectionMaxSelect",
                    1
                )
            )
        );

        assertThat(context).containsEntry("minSelect", 3);
        assertThat(context).containsEntry("maxSelect", 3);
    }
}
