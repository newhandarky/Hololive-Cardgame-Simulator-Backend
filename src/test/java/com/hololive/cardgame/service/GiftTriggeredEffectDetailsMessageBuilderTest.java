package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GiftTriggeredEffectDetailsMessageBuilderTest {

    private final GiftTriggeredEffectDetailsMessageBuilder builder = new GiftTriggeredEffectDetailsMessageBuilder();

    @Test
    void buildGiftTriggeredEffectDetailsShouldReturnEmptyForNoTriggers() {
        assertThat(builder.buildGiftTriggeredEffectDetails(null)).isEmpty();
        assertThat(builder.buildGiftTriggeredEffectDetails(List.of())).isEmpty();
    }

    @Test
    void buildGiftTriggeredEffectDetailsShouldBuildSingleTriggerDetails() {
        String details = builder.buildGiftTriggeredEffectDetails(
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
            )
        );

        assertThat(details).isEqualTo(
            """
            #1 HBP99-001 [COLLAB] 效果類型：DRAW、DAMAGE
            gift text\
            """
        );
    }

    @Test
    void buildGiftTriggeredEffectDetailsShouldBuildMultipleTriggerDetailsAndFallbackSummary() {
        String details = builder.buildGiftTriggeredEffectDetails(
            List.of(
                Map.of("triggerType", "", "requestedEffects", "bad"),
                Map.of("giftHolderCardId", "HBP99-002", "triggerType", "stage_enter", "requestedEffects", List.of("heal"))
            )
        );

        assertThat(details).isEqualTo(
            """
            #1 [GIFT] 效果類型：無可解析效果類型

            #2 HBP99-002 [STAGE_ENTER] 效果類型：HEAL\
            """
        );
    }
}
