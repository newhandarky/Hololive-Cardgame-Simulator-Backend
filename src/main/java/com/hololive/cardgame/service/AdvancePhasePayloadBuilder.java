package com.hololive.cardgame.service;

import com.hololive.cardgame.model.MatchPhase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class AdvancePhasePayloadBuilder {

    private final MatchPhaseAdvanceGiftTransitionService matchPhaseAdvanceGiftTransitionService;
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder;
    private final FollowupDecisionPayloadAppender followupDecisionPayloadAppender;

    AdvancePhasePayloadBuilder(
        MatchPhaseAdvanceGiftTransitionService matchPhaseAdvanceGiftTransitionService,
        GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder,
        FollowupDecisionPayloadAppender followupDecisionPayloadAppender
    ) {
        this.matchPhaseAdvanceGiftTransitionService = matchPhaseAdvanceGiftTransitionService;
        this.giftTriggeredEffectDeferredSummaryBuilder = giftTriggeredEffectDeferredSummaryBuilder;
        this.followupDecisionPayloadAppender = followupDecisionPayloadAppender;
    }

    Map<String, Object> buildAdvancePhasePayload(
        MatchPhase currentPhase,
        MatchPhase nextPhase,
        MatchPhaseAdvanceGiftTransitionService.AdvancePhaseGiftTransition transition,
        AdvancePhaseFollowup followup
    ) {
        AdvancePhaseFollowup safeFollowup = followup == null ? AdvancePhaseFollowup.empty() : followup;
        return buildAdvancePhasePayload(
            currentPhase,
            nextPhase,
            transition,
            safeFollowup.ownGiftEffects(),
            safeFollowup.opponentGiftEffects(),
            safeFollowup.ownDecision(),
            safeFollowup.opponentDecision()
        );
    }

    private Map<String, Object> buildAdvancePhasePayload(
        MatchPhase currentPhase,
        MatchPhase nextPhase,
        MatchPhaseAdvanceGiftTransitionService.AdvancePhaseGiftTransition transition,
        List<Map<String, Object>> ownGiftEffects,
        List<Map<String, Object>> opponentGiftEffects,
        FollowupInteractionDecision ownDecision,
        FollowupInteractionDecision opponentDecision
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromPhase", currentPhase.name());
        payload.put("toPhase", nextPhase.name());
        payload.put("firstPlayerFirstTurnSkip", currentPhase == MatchPhase.MAIN && nextPhase == MatchPhase.END);
        if (transition == null) {
            return payload;
        }
        matchPhaseAdvanceGiftTransitionService.putAdvancePhaseGiftEffectPayload(
            payload,
            transition,
            giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(ownGiftEffects),
            giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(opponentGiftEffects)
        );
        followupDecisionPayloadAppender.append(payload, ownDecision);
        followupDecisionPayloadAppender.appendOpponent(payload, opponentDecision);
        return payload;
    }
}
