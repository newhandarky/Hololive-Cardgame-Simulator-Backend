package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackPostTriggerConfirmMessageBuilderTest {

    private final AttackPostTriggerConfirmMessageBuilder builder = new AttackPostTriggerConfirmMessageBuilder();

    @Test
    void buildAttackArtPostTriggerConfirmMessageShouldReturnDefaultWhenEmpty() {
        assertThat(builder.buildAttackArtPostTriggerConfirmMessage(List.of(), null))
            .isEqualTo("是否要執行攻擊後觸發效果？");
    }

    @Test
    void buildAttackArtPostTriggerConfirmMessageShouldSkipEmptyGiftDetails() {
        assertThat(builder.buildAttackArtPostTriggerConfirmMessage(List.of(Map.of()), null))
            .isEqualTo("是否要執行攻擊後觸發效果？");
    }

    @Test
    void buildAttackArtPostTriggerConfirmMessageShouldIncludeDownEventBeforeGift() {
        String message = builder.buildAttackArtPostTriggerConfirmMessage(
            List.of(
                Map.of(
                    "giftHolderCardId",
                    "HBP99-001",
                    "triggerType",
                    " collab ",
                    "requestedEffects",
                    List.of(" draw ", "DRAW", "", "damage"),
                    "rawText",
                    "gift text"
                )
            ),
            Map.of(
                "requestedLifeLoss",
                "2",
                "downedCardId",
                "HBP99-002",
                "rawText",
                "down text"
            )
        );

        assertThat(message).isEqualTo(
            """
            是否要執行攻擊後觸發效果？
            [Down Event]
            DOWN_EVENT (HBP99-002)：額外失去生命 2
            down text

            [Gift]
            #1 HBP99-001 [COLLAB] 效果類型：DRAW、DAMAGE
            gift text\
            """
        );
    }

    @Test
    void buildGiftTriggeredEffectDetailsShouldUseFallbackSummary() {
        String details = builder.buildGiftTriggeredEffectDetails(
            List.of(Map.of("triggerType", "", "requestedEffects", "bad"))
        );

        assertThat(details).isEqualTo("#1 [GIFT] 效果類型：無可解析效果類型");
    }
}
