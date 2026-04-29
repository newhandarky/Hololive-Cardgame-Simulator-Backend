package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EffectPostTriggerConfirmMessageBuilderTest {

    private final EffectPostTriggerConfirmMessageBuilder builder = new EffectPostTriggerConfirmMessageBuilder();

    @Test
    void buildEffectPostTriggerConfirmMessageShouldUseCardEffectLabelByDefault() {
        String message = builder.buildEffectPostTriggerConfirmMessage(
            "PLAY_SUPPORT",
            Map.of(
                "requestedLifeLoss",
                "2",
                "downedCardId",
                "HBP99-001",
                "rawText",
                "down text"
            )
        );

        assertThat(message).isEqualTo(
            """
            是否要執行此 卡片效果 的後續觸發效果？
            DOWN_EVENT (HBP99-001)：額外失去生命 2
            down text\
            """
        );
    }

    @Test
    void buildEffectPostTriggerConfirmMessageShouldUseOshiSkillLabel() {
        String message = builder.buildEffectPostTriggerConfirmMessage(
            " use_oshi_skill ",
            Map.of("requestedLifeLoss", 1)
        );

        assertThat(message).isEqualTo(
            """
            是否要執行此 Oshi 技能 的後續觸發效果？
            DOWN_EVENT：額外失去生命 1\
            """
        );
    }

    @Test
    void buildEffectPostTriggerConfirmMessageShouldOmitOptionalDetails() {
        String message = builder.buildEffectPostTriggerConfirmMessage(null, null);

        assertThat(message).isEqualTo(
            """
            是否要執行此 卡片效果 的後續觸發效果？
            DOWN_EVENT\
            """
        );
    }
}
