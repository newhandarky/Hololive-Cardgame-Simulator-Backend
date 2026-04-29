package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class GiftPendingDecisionCreator {

    private final GiftTriggerInteractionCardsBuilder giftTriggerInteractionCardsBuilder;
    private final GiftTriggeredEffectConfirmPendingInputBuilder giftTriggeredEffectConfirmPendingInputBuilder;
    private final FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter;

    GiftPendingDecisionCreator(
        GiftTriggerInteractionCardsBuilder giftTriggerInteractionCardsBuilder,
        GiftTriggeredEffectConfirmPendingInputBuilder giftTriggeredEffectConfirmPendingInputBuilder,
        FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter
    ) {
        this.giftTriggerInteractionCardsBuilder = giftTriggerInteractionCardsBuilder;
        this.giftTriggeredEffectConfirmPendingInputBuilder = giftTriggeredEffectConfirmPendingInputBuilder;
        this.followupTriggerConfirmPendingDecisionWriter = followupTriggerConfirmPendingDecisionWriter;
    }

    FollowupInteractionDecision createWithGiftTriggerInteractionCards(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> giftTriggeredEffects,
        int turnNumber
    ) {
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return null;
        }
        return createWithCards(
            matchId,
            userId,
            sourceCardInstanceId,
            sourceCardId,
            giftTriggerInteractionCardsBuilder.buildGiftTriggerInteractionCards(
                matchId,
                userId,
                sourceCardInstanceId,
                sourceCardId,
                giftTriggeredEffects
            ),
            giftTriggeredEffects,
            turnNumber
        );
    }

    FollowupInteractionDecision createWithCards(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> cards,
        List<Map<String, Object>> giftTriggeredEffects,
        int turnNumber
    ) {
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return null;
        }
        FollowupTriggerConfirmPendingDecisionInput input = giftTriggeredEffectConfirmPendingInputBuilder
            .buildGiftTriggeredEffectConfirmPendingInput(
                matchId,
                userId,
                sourceCardInstanceId,
                sourceCardId,
                cards,
                giftTriggeredEffects,
                turnNumber
            );
        return followupTriggerConfirmPendingDecisionWriter.create(input);
    }
}
