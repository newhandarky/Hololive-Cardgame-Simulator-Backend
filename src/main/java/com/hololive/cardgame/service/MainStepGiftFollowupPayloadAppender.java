package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class MainStepGiftFollowupPayloadAppender {

    private final MatchGiftTriggerService matchGiftTriggerService;
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder;
    private final SourcelessGiftPendingDecisionCreator sourcelessGiftPendingDecisionCreator;
    private final FollowupDecisionPayloadAppender followupDecisionPayloadAppender;

    MainStepGiftFollowupPayloadAppender(
        MatchGiftTriggerService matchGiftTriggerService,
        GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder,
        SourcelessGiftPendingDecisionCreator sourcelessGiftPendingDecisionCreator,
        FollowupDecisionPayloadAppender followupDecisionPayloadAppender
    ) {
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.giftTriggeredEffectDeferredSummaryBuilder = giftTriggeredEffectDeferredSummaryBuilder;
        this.sourcelessGiftPendingDecisionCreator = sourcelessGiftPendingDecisionCreator;
        this.followupDecisionPayloadAppender = followupDecisionPayloadAppender;
    }

    void append(Map<String, Object> payload, Long matchId, Long userId, int turnNumber) {
        List<Map<String, Object>> mainStepGiftEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnOwnMainStep(
            matchId,
            userId,
            turnNumber
        );
        payload.put(
            "mainStepGiftEffects",
            giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(mainStepGiftEffects)
        );
        if (mainStepGiftEffects.isEmpty()) {
            return;
        }
        FollowupInteractionDecision mainStepGiftDecision = sourcelessGiftPendingDecisionCreator.create(
            matchId,
            userId,
            mainStepGiftEffects,
            turnNumber
        );
        followupDecisionPayloadAppender.append(payload, mainStepGiftDecision);
    }
}
