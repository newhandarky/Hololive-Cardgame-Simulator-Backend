package com.hololive.cardgame.service;

import java.util.Map;

class EffectFollowupDecisionResolver {

    private final EffectPostTriggerPendingService effectPostTriggerPendingService;
    private final FollowupInteractionContextResolver followupInteractionContextResolver;
    private final FollowupInteractionPendingDecisionWriter followupInteractionPendingDecisionWriter;

    EffectFollowupDecisionResolver(
        EffectPostTriggerPendingService effectPostTriggerPendingService,
        FollowupInteractionContextResolver followupInteractionContextResolver,
        FollowupInteractionPendingDecisionWriter followupInteractionPendingDecisionWriter
    ) {
        this.effectPostTriggerPendingService = effectPostTriggerPendingService;
        this.followupInteractionContextResolver = followupInteractionContextResolver;
        this.followupInteractionPendingDecisionWriter = followupInteractionPendingDecisionWriter;
    }

    FollowupInteractionDecision resolvePostTriggerOrInteraction(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        Map<String, Object> effectSummary,
        int turnNumber
    ) {
        FollowupInteractionDecision decision =
            effectPostTriggerPendingService.createEffectPostTriggerConfirmPendingInteractionIfNeeded(
                matchId,
                userId,
                sourceActionType,
                sourceCardInstanceId,
                sourceCardId,
                effectSummary,
                turnNumber
            );
        if (decision != null) {
            return decision;
        }
        return resolveInteraction(matchId, userId, sourceActionType, sourceCardInstanceId, sourceCardId, effectType, effectSummary);
    }

    FollowupInteractionDecision resolveInteraction(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        Map<String, Object> effectSummary
    ) {
        FollowupInteractionContext interaction = followupInteractionContextResolver.resolve(matchId, userId, effectSummary);
        if (interaction == null) {
            return null;
        }
        return followupInteractionPendingDecisionWriter.create(
            matchId,
            userId,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            interaction
        );
    }
}
