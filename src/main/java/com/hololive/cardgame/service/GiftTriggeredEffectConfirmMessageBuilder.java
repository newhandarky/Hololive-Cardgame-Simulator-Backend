package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class GiftTriggeredEffectConfirmMessageBuilder {

    private static final String DEFAULT_CONFIRM_MESSAGE = "是否要執行本次 Gift 觸發效果？";

    private final GiftTriggeredEffectDetailsMessageBuilder giftTriggeredEffectDetailsMessageBuilder;

    GiftTriggeredEffectConfirmMessageBuilder() {
        this.giftTriggeredEffectDetailsMessageBuilder = new GiftTriggeredEffectDetailsMessageBuilder();
    }

    String buildGiftTriggeredEffectConfirmMessage(List<Map<String, Object>> giftTriggeredEffects) {
        String details = giftTriggeredEffectDetailsMessageBuilder.buildGiftTriggeredEffectDetails(giftTriggeredEffects);
        if (details == null || details.trim().isEmpty()) {
            return DEFAULT_CONFIRM_MESSAGE;
        }
        return DEFAULT_CONFIRM_MESSAGE + "\n" + details;
    }
}
