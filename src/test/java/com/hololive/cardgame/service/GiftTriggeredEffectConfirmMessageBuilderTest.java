package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GiftTriggeredEffectConfirmMessageBuilderTest {

    private final GiftTriggeredEffectConfirmMessageBuilder builder = new GiftTriggeredEffectConfirmMessageBuilder();

    @Test
    void buildGiftTriggeredEffectConfirmMessageShouldReturnDefaultForNoTriggers() {
        assertThat(builder.buildGiftTriggeredEffectConfirmMessage(null))
            .isEqualTo("是否要執行本次 Gift 觸發效果？");
        assertThat(builder.buildGiftTriggeredEffectConfirmMessage(List.of()))
            .isEqualTo("是否要執行本次 Gift 觸發效果？");
        assertThat(builder.buildGiftTriggeredEffectConfirmMessage(List.of(Map.of())))
            .isEqualTo("是否要執行本次 Gift 觸發效果？");
    }

    @Test
    void buildGiftTriggeredEffectConfirmMessageShouldAppendGiftDetails() {
        String message = builder.buildGiftTriggeredEffectConfirmMessage(
            List.of(
                Map.of(
                    "giftHolderCardId",
                    "HBP99-001",
                    "triggerType",
                    " stage_enter ",
                    "requestedEffects",
                    List.of(" draw ", "DRAW", "heal"),
                    "rawText",
                    "gift text"
                )
            )
        );

        assertThat(message).isEqualTo(
            """
            是否要執行本次 Gift 觸發效果？
            #1 HBP99-001 [STAGE_ENTER] 效果類型：DRAW、HEAL
            gift text\
            """
        );
    }

    @Test
    void buildGiftTriggeredEffectConfirmMessageShouldUseFallbackDetails() {
        String message = builder.buildGiftTriggeredEffectConfirmMessage(
            List.of(Map.of("triggerType", "", "requestedEffects", "bad"))
        );

        assertThat(message).isEqualTo(
            """
            是否要執行本次 Gift 觸發效果？
            #1 [GIFT] 效果類型：無可解析效果類型\
            """
        );
    }
}
