package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PlayCardEffectResolutionService {

    private final MatchGiftTriggerService matchGiftTriggerService;
    private final MatchEventHookService matchEventHookService;
    private final FollowupSourceCardPayloadBuilder followupSourceCardPayloadBuilder;
    private final FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter;
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder;
    private final GiftTriggeredEffectConfirmPendingInputBuilder giftTriggeredEffectConfirmPendingInputBuilder;

    public PlayCardEffectResolutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchGiftTriggerService matchGiftTriggerService,
        MatchEventHookService matchEventHookService
    ) {
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.matchEventHookService = matchEventHookService;
        this.followupSourceCardPayloadBuilder = new FollowupSourceCardPayloadBuilder(jdbcTemplate);
        this.followupTriggerConfirmPendingDecisionWriter = new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, objectMapper);
        this.giftTriggeredEffectDeferredSummaryBuilder = new GiftTriggeredEffectDeferredSummaryBuilder();
        this.giftTriggeredEffectConfirmPendingInputBuilder = new GiftTriggeredEffectConfirmPendingInputBuilder();
    }

    public PlayCardEffectResolution resolve(PlayCardAction action, PlayCardResolutionResult resolutionResult) {
        if (action == null || resolutionResult == null) {
            throw new IllegalArgumentException("PLAY_CARD effect 結算缺少必要上下文");
        }

        Long matchId = action.matchId();
        Long userId = action.actorUserId();
        if (resolutionResult.openingReset()) {
            return new PlayCardEffectResolution(
                Map.of("deferredUntilLiveStart", true),
                List.of(),
                Map.of(),
                null,
                null,
                true,
                List.of()
            );
        }

        Map<String, Object> triggerSummary = matchEventHookService.onHolomemEnter(
            matchId,
            userId,
            resolutionResult.cardId(),
            resolutionResult.cardInstanceId(),
            resolutionResult.targetZone()
        );
        List<Map<String, Object>> giftTriggeredEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnStageEnter(
            matchId,
            userId,
            resolutionResult.cardInstanceId(),
            resolutionResult.targetZone(),
            resolutionResult.turnNumber()
        );
        Map<String, Object> giftEffectSummary = buildGiftTriggeredEffectDeferredSummary(giftTriggeredEffects);

        FollowupInteractionDecision pendingDecision = null;
        if (!giftTriggeredEffects.isEmpty()) {
            pendingDecision = createGiftTriggeredEffectConfirmPendingInteraction(
                matchId,
                userId,
                resolutionResult.cardInstanceId(),
                resolutionResult.cardId(),
                List.of(
                    followupSourceCardPayloadBuilder.buildOwnedCard(
                        matchId,
                        userId,
                        resolutionResult.cardInstanceId(),
                        resolutionResult.targetZone(),
                        resolutionResult.cardId()
                    )
                ),
                giftTriggeredEffects,
                resolutionResult.turnNumber()
            );
        }

        return new PlayCardEffectResolution(
            triggerSummary,
            giftTriggeredEffects,
            giftEffectSummary,
            pendingDecision == null ? null : pendingDecision.decisionId(),
            pendingDecision == null ? null : pendingDecision.decisionType(),
            false,
            buildTriggeredResolutionOrder(
                "GIFT_TRIGGER",
                100,
                giftEffectSummary,
                "ENTER_EVENT_HOOK",
                200,
                triggerSummary
            )
        );
    }

    private Map<String, Object> buildGiftTriggeredEffectDeferredSummary(List<Map<String, Object>> giftTriggeredEffects) {
        return giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(giftTriggeredEffects);
    }

    private FollowupInteractionDecision createGiftTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> cards,
        List<Map<String, Object>> giftTriggeredEffects,
        int turnNumber
    ) {
        return followupTriggerConfirmPendingDecisionWriter.create(
            giftTriggeredEffectConfirmPendingInputBuilder.buildGiftTriggeredEffectConfirmPendingInput(
                matchId,
                userId,
                sourceCardInstanceId,
                sourceCardId,
                cards,
                giftTriggeredEffects,
                turnNumber
            )
        );
    }

    private List<Map<String, Object>> buildTriggeredResolutionOrder(
        String firstStep,
        int firstPriority,
        Map<String, Object> firstSummary,
        String secondStep,
        int secondPriority,
        Map<String, Object> secondSummary
    ) {
        List<Map<String, Object>> order = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("step", firstStep);
        first.put("priority", firstPriority);
        first.put("applied", firstSummary != null);
        order.add(first);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("step", secondStep);
        second.put("priority", secondPriority);
        second.put("applied", secondSummary != null);
        order.add(second);
        return order;
    }

}
