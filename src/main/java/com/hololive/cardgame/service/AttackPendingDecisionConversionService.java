package com.hololive.cardgame.service;

class AttackPendingDecisionConversionService {

    FollowupInteractionDecision toFollowupInteractionDecision(AttackPendingDecision decision) {
        if (decision == null) {
            return null;
        }
        return new FollowupInteractionDecision(decision.decisionId(), decision.decisionType());
    }

    AttackPendingDecision toAttackPendingDecision(FollowupInteractionDecision decision) {
        if (decision == null) {
            return null;
        }
        return new AttackPendingDecision(decision.decisionId(), decision.decisionType());
    }
}
