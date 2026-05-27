package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

class MatchSupportCardSelectionResolutionService {

    private static final String ACTION_TYPE_PLAY_SUPPORT = "PLAY_SUPPORT";
    private static final String ACTION_TYPE_USE_OSHI_SKILL = "USE_OSHI_SKILL";

    private final PendingDecisionStore pendingDecisionStore;
    private final MatchRepository matchRepository;
    private final MatchActionRepository matchActionRepository;
    private final MatchPayloadJsonService matchPayloadJsonService;
    private final MatchTimestampService matchTimestampService;
    private final SelectedCardValidationService selectedCardValidationService;
    private final SupportOshiEffectPayloadBuilder supportOshiEffectPayloadBuilder;
    private final EffectFollowupDecisionResolver effectFollowupDecisionResolver;
    private final FollowupDecisionPayloadAppender followupDecisionPayloadAppender;
    private final SupportEffectApplier supportEffectApplier;
    private final ResolvedEffectFinalizer resolvedEffectFinalizer;

    MatchSupportCardSelectionResolutionService(
        PendingDecisionStore pendingDecisionStore,
        MatchRepository matchRepository,
        MatchActionRepository matchActionRepository,
        MatchPayloadJsonService matchPayloadJsonService,
        MatchTimestampService matchTimestampService,
        SelectedCardValidationService selectedCardValidationService,
        SupportOshiEffectPayloadBuilder supportOshiEffectPayloadBuilder,
        EffectFollowupDecisionResolver effectFollowupDecisionResolver,
        FollowupDecisionPayloadAppender followupDecisionPayloadAppender,
        SupportEffectApplier supportEffectApplier,
        ResolvedEffectFinalizer resolvedEffectFinalizer
    ) {
        this.pendingDecisionStore = pendingDecisionStore;
        this.matchRepository = matchRepository;
        this.matchActionRepository = matchActionRepository;
        this.matchPayloadJsonService = matchPayloadJsonService;
        this.matchTimestampService = matchTimestampService;
        this.selectedCardValidationService = selectedCardValidationService;
        this.supportOshiEffectPayloadBuilder = supportOshiEffectPayloadBuilder;
        this.effectFollowupDecisionResolver = effectFollowupDecisionResolver;
        this.followupDecisionPayloadAppender = followupDecisionPayloadAppender;
        this.supportEffectApplier = supportEffectApplier;
        this.resolvedEffectFinalizer = resolvedEffectFinalizer;
    }

    void resolve(
        Long matchId,
        Long userId,
        int turnNumber,
        MatchEntity match,
        PendingDecision pending,
        ResolveDecisionRequest request
    ) {
        List<Long> selectedCardInstanceIds = selectedCardValidationService.validate(
            request == null ? null : request.getSelectedCardInstanceIds(),
            pending.minSelect(),
            pending.maxSelect(),
            pending.candidateCardInstanceIds()
        );

        Map<String, Object> effectSummary = supportEffectApplier.apply(matchId, userId, pending, selectedCardInstanceIds);
        pendingDecisionStore.markResolved(pending.decisionId());
        transitionMatchToMainAndSave(match);

        String sourceActionType = MatchEffectValueHelper.normalize(pending.sourceActionType());
        String resolvedActionType = ACTION_TYPE_USE_OSHI_SKILL.equals(sourceActionType)
            ? ACTION_TYPE_USE_OSHI_SKILL
            : ACTION_TYPE_PLAY_SUPPORT;
        Map<String, Object> payload = supportOshiEffectPayloadBuilder.buildResolvedSelectionEffectPayload(
            pending.decisionId(),
            sourceActionType,
            pending.sourceCardInstanceId(),
            pending.sourceCardId(),
            pending.limited(),
            pending.targetHolomemCardInstanceId(),
            selectedCardInstanceIds,
            effectSummary
        );
        FollowupInteractionDecision followupDecision = effectFollowupDecisionResolver.resolvePostTriggerOrInteraction(
            matchId,
            userId,
            sourceActionType,
            pending.sourceCardInstanceId(),
            pending.sourceCardId(),
            pending.effectType(),
            effectSummary,
            turnNumber
        );
        followupDecisionPayloadAppender.append(payload, followupDecision);
        appendAction(match, userId, resolvedActionType, matchPayloadJsonService.toJson(payload), turnNumber);
        resolvedEffectFinalizer.finalizeResolvedEffect(match, matchId, userId, turnNumber, effectSummary);
    }

    private MatchActionEntity appendAction(
        MatchEntity match,
        Long userId,
        String actionType,
        String payload,
        int turnNumber
    ) {
        MatchActionEntity action = new MatchActionEntity();
        action.setMatchId(match.getId());
        action.setUserId(userId);
        action.setActionType(actionType);
        action.setPayload(payload);
        action.setTurnNumber(turnNumber);
        action.setActionOrder(matchActionRepository.findMaxActionOrderByTurn(match.getId(), turnNumber) + 1);
        action.setExecutedAt(LocalDateTime.now());
        return matchActionRepository.save(action);
    }

    private void transitionMatchToMainAndSave(MatchEntity match) {
        match.setCurrentPhase(MatchPhase.MAIN.name());
        matchTimestampService.touchUpdatedAt(match);
        matchRepository.saveAndFlush(match);
    }

    @FunctionalInterface
    interface SupportEffectApplier {
        Map<String, Object> apply(
            Long matchId,
            Long userId,
            PendingDecision pending,
            List<Long> selectedCardInstanceIds
        );
    }

    @FunctionalInterface
    interface ResolvedEffectFinalizer {
        void finalizeResolvedEffect(
            MatchEntity match,
            Long matchId,
            Long userId,
            int turnNumber,
            Map<String, Object> effectSummary
        );
    }
}
