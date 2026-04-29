package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackArtPostTriggerConfirmPendingInputBuilderTest {

    private final AttackArtPostTriggerConfirmPendingInputBuilder builder =
        new AttackArtPostTriggerConfirmPendingInputBuilder();

    @Test
    void buildAttackArtPostTriggerConfirmPendingInputShouldBuildGiftAndDownEventContext() {
        Map<String, Object> card = Map.of("cardInstanceId", 701L, "cardId", "hBP01-001", "zone", "CENTER");
        Map<String, Object> giftTrigger = Map.of(
            "triggerType",
            "ART_USED",
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
        Map<String, Object> downEventPreview = Map.of(
            "downedOwnerUserId",
            20L,
            "downedCardId",
            "hBP02-001",
            "downedStageZone",
            "CENTER",
            "turnNumber",
            4,
            "requestedLifeLoss",
            1,
            "rawText",
            "down text"
        );

        FollowupTriggerConfirmPendingDecisionInput input = builder.buildAttackArtPostTriggerConfirmPendingInput(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(card),
            List.of(giftTrigger),
            downEventPreview,
            4
        );

        assertThat(input.matchId()).isEqualTo(100L);
        assertThat(input.userId()).isEqualTo(10L);
        assertThat(input.sourceActionType()).isEqualTo("ATTACK_ART_POST_TRIGGER");
        assertThat(input.sourceCardInstanceId()).isEqualTo(701L);
        assertThat(input.sourceCardId()).isEqualTo("hBP01-001");
        assertThat(input.effectType()).isEqualTo("ATTACK_ART_POST_TRIGGER");
        assertThat(input.title()).isEqualTo("確認攻擊後觸發效果");
        assertThat(input.cards()).containsExactly(card);
        assertThat(input.turnNumber()).isEqualTo(4);
        assertThat(input.message())
            .contains("是否要執行攻擊後觸發效果？")
            .contains("[Down Event]")
            .contains("[Gift]");
        assertThat(input.additionalContext())
            .containsEntry("giftCount", 1)
            .containsKeys("giftTriggers", "downEvent", "triggerSections")
            .doesNotContainKeys("minSelect", "maxSelect");
        @SuppressWarnings("unchecked")
        Map<String, Object> downEvent = (Map<String, Object>) input.additionalContext().get("downEvent");
        assertThat(downEvent)
            .containsEntry("downedCardId", "hBP02-001")
            .containsEntry("requestedLifeLoss", 1);
        assertThat((List<?>) input.additionalContext().get("triggerSections")).hasSize(2);
    }

    @Test
    void buildAttackArtPostTriggerConfirmPendingInputShouldAppendSelectionContext() {
        Map<String, Object> giftTrigger = Map.of(
            "triggerType",
            "ART_USED",
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

        FollowupTriggerConfirmPendingDecisionInput input = builder.buildAttackArtPostTriggerConfirmPendingInput(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(),
            List.of(giftTrigger),
            null,
            4
        );

        assertThat(input.additionalContext())
            .containsEntry("giftCount", 1)
            .containsEntry("candidateCardInstanceIds", List.of(901L, 902L))
            .containsEntry("selectionGiftHolderCardInstanceId", 801L)
            .containsEntry("minSelect", 1)
            .containsEntry("maxSelect", 2)
            .doesNotContainKey("downEvent");
    }
}
