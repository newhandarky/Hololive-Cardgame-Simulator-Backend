package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class SourcelessGiftPendingDecisionCreator {

    private final GiftPendingDecisionCreator giftPendingDecisionCreator;

    SourcelessGiftPendingDecisionCreator(GiftPendingDecisionCreator giftPendingDecisionCreator) {
        this.giftPendingDecisionCreator = giftPendingDecisionCreator;
    }

    FollowupInteractionDecision create(
        Long matchId,
        Long userId,
        List<Map<String, Object>> giftEffects,
        int turnNumber
    ) {
        return giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
            matchId,
            userId,
            null,
            null,
            giftEffects,
            turnNumber
        );
    }
}
