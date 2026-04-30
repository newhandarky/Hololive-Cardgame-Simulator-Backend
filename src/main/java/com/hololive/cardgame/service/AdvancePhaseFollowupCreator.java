package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class AdvancePhaseFollowupCreator {

    private final MatchPhaseAdvanceGiftTransitionService matchPhaseAdvanceGiftTransitionService;
    private final SourcelessGiftPendingDecisionCreator sourcelessGiftPendingDecisionCreator;

    AdvancePhaseFollowupCreator(
        MatchPhaseAdvanceGiftTransitionService matchPhaseAdvanceGiftTransitionService,
        SourcelessGiftPendingDecisionCreator sourcelessGiftPendingDecisionCreator
    ) {
        this.matchPhaseAdvanceGiftTransitionService = matchPhaseAdvanceGiftTransitionService;
        this.sourcelessGiftPendingDecisionCreator = sourcelessGiftPendingDecisionCreator;
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
        FollowupInteractionDecision ownDecision = sourcelessGiftPendingDecisionCreator.create(
            matchId,
            userId,
            ownGiftEffects,
            turnNumber
        );

        List<Map<String, Object>> opponentGiftEffects = transitionPreview.opponentGiftEffects();
        FollowupInteractionDecision opponentDecision = null;
        if (opponentUserId != null) {
            opponentDecision = sourcelessGiftPendingDecisionCreator.create(
                matchId,
                opponentUserId,
                opponentGiftEffects,
                turnNumber
            );
        }
        return new AdvancePhaseFollowup(
            ownGiftEffects,
            opponentGiftEffects,
            ownDecision,
            opponentDecision
        );
    }
}
