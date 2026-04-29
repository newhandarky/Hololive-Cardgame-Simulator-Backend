package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class FollowupTriggerConfirmPendingDecisionCreator {

    private final FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter;

    FollowupTriggerConfirmPendingDecisionCreator(
        FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter
    ) {
        this.followupTriggerConfirmPendingDecisionWriter = followupTriggerConfirmPendingDecisionWriter;
    }

    FollowupInteractionDecision create(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        String title,
        String message,
        List<Map<String, Object>> cards,
        int turnNumber,
        Map<String, Object> additionalContext
    ) {
        return followupTriggerConfirmPendingDecisionWriter.create(new FollowupTriggerConfirmPendingDecisionInput(
            matchId,
            userId,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            title,
            message,
            cards,
            turnNumber,
            additionalContext
        ));
    }
}
