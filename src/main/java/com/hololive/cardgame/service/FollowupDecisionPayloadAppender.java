package com.hololive.cardgame.service;

import java.util.Map;

class FollowupDecisionPayloadAppender {

    private static final String DECISION_TYPE_LOOK_TOP_DECK = "LOOK_TOP_DECK";

    void append(Map<String, Object> payload, FollowupInteractionDecision followupDecision) {
        if (payload == null || followupDecision == null || followupDecision.decisionId() == null) {
            return;
        }
        payload.put("pendingInteractionDecisionId", followupDecision.decisionId());
        payload.put("pendingInteractionDecisionType", followupDecision.decisionType());
        if (DECISION_TYPE_LOOK_TOP_DECK.equals(followupDecision.decisionType())) {
            payload.put("pendingLookTopDeckDecisionId", followupDecision.decisionId());
        }
    }

    void appendOpponent(Map<String, Object> payload, FollowupInteractionDecision followupDecision) {
        if (payload == null || followupDecision == null) {
            return;
        }
        payload.put("opponentPendingInteractionDecisionId", followupDecision.decisionId());
        payload.put("opponentPendingInteractionDecisionType", followupDecision.decisionType());
    }
}
