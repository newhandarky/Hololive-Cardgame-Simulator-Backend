package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class BatonTouchGiftFollowupCreator {

    private final MatchGiftTriggerService matchGiftTriggerService;
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder;
    private final GiftPendingDecisionCreator giftPendingDecisionCreator;

    BatonTouchGiftFollowupCreator(
        MatchGiftTriggerService matchGiftTriggerService,
        GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder,
        GiftPendingDecisionCreator giftPendingDecisionCreator
    ) {
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.giftTriggeredEffectDeferredSummaryBuilder = giftTriggeredEffectDeferredSummaryBuilder;
        this.giftPendingDecisionCreator = giftPendingDecisionCreator;
    }

    BatonTouchGiftFollowup create(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        int turnNumber
    ) {
        List<Map<String, Object>> giftEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnBatonTouchBack(
            matchId,
            userId,
            sourceCardInstanceId,
            turnNumber
        );
        if (giftEffects.isEmpty()) {
            return BatonTouchGiftFollowup.empty();
        }
        return new BatonTouchGiftFollowup(
            giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(giftEffects),
            giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
                matchId,
                userId,
                sourceCardInstanceId,
                sourceCardId,
                giftEffects,
                turnNumber
            )
        );
    }
}
