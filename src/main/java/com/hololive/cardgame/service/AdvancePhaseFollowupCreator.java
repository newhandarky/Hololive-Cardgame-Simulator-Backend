package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class AdvancePhaseFollowupCreator {

    private final MatchPhaseAdvanceGiftTransitionService matchPhaseAdvanceGiftTransitionService;
    private final GiftPendingDecisionCreator giftPendingDecisionCreator;

    AdvancePhaseFollowupCreator(
        MatchPhaseAdvanceGiftTransitionService matchPhaseAdvanceGiftTransitionService,
        GiftPendingDecisionCreator giftPendingDecisionCreator
    ) {
        this.matchPhaseAdvanceGiftTransitionService = matchPhaseAdvanceGiftTransitionService;
        this.giftPendingDecisionCreator = giftPendingDecisionCreator;
    }

    AdvancePhaseFollowup prepareAdvancePhaseFollowup(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber,
        MatchPhaseAdvanceGiftTransitionService.AdvancePhaseGiftTransition transition
    ) {
        if (transition == null) {
            return AdvancePhaseFollowup.empty();
        }
        return createAdvancePhaseFollowup(
            matchId,
            userId,
            opponentUserId,
            turnNumber,
            matchPhaseAdvanceGiftTransitionService.prepareAdvancePhaseTransition(
                transition,
                matchId,
                userId,
                opponentUserId,
                turnNumber
            )
        );
    }

    AdvancePhaseFollowup createAdvancePhaseFollowup(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber,
        MatchPhaseAdvanceGiftTransitionService.GiftTransitionPreview transitionPreview
    ) {
        if (transitionPreview == null) {
            return AdvancePhaseFollowup.empty();
        }
        List<Map<String, Object>> ownGiftEffects = transitionPreview.ownGiftEffects();
        FollowupInteractionDecision ownDecision = createGiftTriggerDecisionWithoutSourceCard(
            matchId,
            userId,
            turnNumber,
            ownGiftEffects
        );

        List<Map<String, Object>> opponentGiftEffects = transitionPreview.opponentGiftEffects();
        FollowupInteractionDecision opponentDecision = null;
        if (opponentUserId != null) {
            opponentDecision = createGiftTriggerDecisionWithoutSourceCard(
                matchId,
                opponentUserId,
                turnNumber,
                opponentGiftEffects
            );
        }
        return new AdvancePhaseFollowup(
            ownGiftEffects,
            opponentGiftEffects,
            ownDecision,
            opponentDecision
        );
    }

    private FollowupInteractionDecision createGiftTriggerDecisionWithoutSourceCard(
        Long matchId,
        Long userId,
        int turnNumber,
        List<Map<String, Object>> giftEffects
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
