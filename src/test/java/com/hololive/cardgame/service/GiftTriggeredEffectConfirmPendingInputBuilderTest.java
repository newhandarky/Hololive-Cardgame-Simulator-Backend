package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GiftTriggeredEffectConfirmPendingInputBuilderTest {

    private final GiftTriggeredEffectConfirmPendingInputBuilder builder = new GiftTriggeredEffectConfirmPendingInputBuilder();

    @Test
    void buildGiftTriggeredEffectConfirmPendingInputShouldBuildFixedInputShape() {
        Map<String, Object> card = Map.of("cardInstanceId", 701L, "cardId", "hBP01-001", "zone", "BACK");
        Map<String, Object> trigger = Map.of(
            "triggerType",
            "STAGE_ENTER",
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK",
            "requestedEffects",
            List.of("DRAW"),
            "rawText",
            "gift text"
        );

        FollowupTriggerConfirmPendingDecisionInput input = builder.buildGiftTriggeredEffectConfirmPendingInput(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(card),
            List.of(trigger),
            4
        );

        assertThat(input.matchId()).isEqualTo(100L);
        assertThat(input.userId()).isEqualTo(10L);
        assertThat(input.sourceActionType()).isEqualTo("GIFT");
        assertThat(input.sourceCardInstanceId()).isEqualTo(701L);
        assertThat(input.sourceCardId()).isEqualTo("hBP01-001");
        assertThat(input.effectType()).isEqualTo("GIFT_TRIGGER");
        assertThat(input.title()).isEqualTo("確認 Gift 效果");
        assertThat(input.cards()).containsExactly(card);
        assertThat(input.turnNumber()).isEqualTo(4);
        assertThat(input.message()).isEqualTo(
            """
            是否要執行本次 Gift 觸發效果？
            #1 hBP06-014 [STAGE_ENTER] 效果類型：DRAW
            gift text\
            """
        );
        assertThat(input.additionalContext())
            .containsEntry("giftCount", 1)
            .doesNotContainKeys("minSelect", "maxSelect");
        assertThat((List<?>) input.additionalContext().get("giftTriggers")).hasSize(1);
    }

    @Test
    void buildGiftTriggeredEffectConfirmPendingInputShouldUseDefaultMessageForNoTriggers() {
        FollowupTriggerConfirmPendingDecisionInput input = builder.buildGiftTriggeredEffectConfirmPendingInput(
            100L,
            10L,
            null,
            null,
            List.of(),
            List.of(),
            4
        );

        assertThat(input.message()).isEqualTo("是否要執行本次 Gift 觸發效果？");
        assertThat(input.cards()).isEmpty();
        assertThat(input.additionalContext())
            .containsEntry("giftTriggers", List.of())
            .containsEntry("giftCount", 0)
            .doesNotContainKeys("minSelect", "maxSelect");
    }

    @Test
    void buildGiftTriggeredEffectConfirmPendingInputShouldAppendSelectionContext() {
        Map<String, Object> trigger = Map.of(
            "triggerType",
            "STAGE_ENTER",
            "giftHolderCardInstanceId",
            801L,
            "requestedEffects",
            List.of("SEARCH"),
            "selectionRequired",
            true,
            "selectionCandidateCardInstanceIds",
            List.of(901L, 902L),
            "selectionMinSelect",
            1,
            "selectionMaxSelect",
            2
        );

        FollowupTriggerConfirmPendingDecisionInput input = builder.buildGiftTriggeredEffectConfirmPendingInput(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(),
            List.of(trigger),
            4
        );

        assertThat(input.additionalContext())
            .containsEntry("giftCount", 1)
            .containsEntry("candidateCardInstanceIds", List.of(901L, 902L))
            .containsEntry("selectionGiftHolderCardInstanceId", 801L)
            .containsEntry("minSelect", 1)
            .containsEntry("maxSelect", 2);
    }
}
