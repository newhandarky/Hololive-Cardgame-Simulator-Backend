package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class GiftTriggeredEffectConfirmPendingInputBuilder {

    private final GiftTriggerPendingPayloadBuilder giftTriggerPendingPayloadBuilder;
    private final GiftSelectionPendingContextBuilder giftSelectionPendingContextBuilder;
    private final GiftTriggeredEffectConfirmMessageBuilder giftTriggeredEffectConfirmMessageBuilder;

    GiftTriggeredEffectConfirmPendingInputBuilder() {
        this.giftTriggerPendingPayloadBuilder = new GiftTriggerPendingPayloadBuilder();
        this.giftSelectionPendingContextBuilder = new GiftSelectionPendingContextBuilder();
        this.giftTriggeredEffectConfirmMessageBuilder = new GiftTriggeredEffectConfirmMessageBuilder();
    }

    FollowupTriggerConfirmPendingDecisionInput buildGiftTriggeredEffectConfirmPendingInput(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> cards,
        List<Map<String, Object>> giftTriggeredEffects,
        int turnNumber
    ) {
        List<Map<String, Object>> giftTriggers = giftTriggerPendingPayloadBuilder.buildGiftTriggerPayloads(giftTriggeredEffects);
        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("giftTriggers", giftTriggers);
        additionalContext.put("giftCount", giftTriggers.size());
        additionalContext.putAll(giftSelectionPendingContextBuilder.buildSelectionPendingContext(giftTriggeredEffects));

        return new FollowupTriggerConfirmPendingDecisionInput(
            matchId,
            userId,
            "GIFT",
            sourceCardInstanceId,
            sourceCardId,
            "GIFT_TRIGGER",
            "確認 Gift 效果",
            giftTriggeredEffectConfirmMessageBuilder.buildGiftTriggeredEffectConfirmMessage(giftTriggeredEffects),
            cards,
            turnNumber,
            additionalContext
        );
    }
}
