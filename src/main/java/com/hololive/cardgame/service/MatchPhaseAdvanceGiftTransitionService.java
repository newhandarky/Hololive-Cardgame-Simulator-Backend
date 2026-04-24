package com.hololive.cardgame.service;

import com.hololive.cardgame.model.MatchPhase;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MatchPhaseAdvanceGiftTransitionService {

    private final MatchGiftTriggerService matchGiftTriggerService;

    public MatchPhaseAdvanceGiftTransitionService(MatchGiftTriggerService matchGiftTriggerService) {
        this.matchGiftTriggerService = matchGiftTriggerService;
    }

    public AdvancePhaseGiftTransition resolveAdvancePhaseTransition(MatchPhase currentPhase, MatchPhase nextPhase) {
        if (currentPhase == MatchPhase.MAIN && nextPhase == MatchPhase.PERFORMANCE) {
            return AdvancePhaseGiftTransition.forPerformanceStart();
        }
        if (currentPhase == MatchPhase.PERFORMANCE && nextPhase == MatchPhase.END) {
            return AdvancePhaseGiftTransition.forPerformanceEnd();
        }
        return null;
    }

    public GiftTransitionPreview prepareAdvancePhaseTransition(
        AdvancePhaseGiftTransition transition,
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber
    ) {
        if (transition == null) {
            return null;
        }
        return transition.performanceStart()
            ? preparePerformanceStartTransition(matchId, userId, opponentUserId, turnNumber)
            : preparePerformanceEndTransition(matchId, userId, opponentUserId, turnNumber);
    }

    public void putAdvancePhaseGiftEffectPayload(
        Map<String, Object> payload,
        AdvancePhaseGiftTransition transition,
        Map<String, Object> ownGiftEffectSummary,
        Map<String, Object> opponentGiftEffectSummary
    ) {
        if (payload == null || transition == null) {
            return;
        }
        payload.put(transition.ownGiftEffectsPayloadKey(), ownGiftEffectSummary == null ? new LinkedHashMap<>() : ownGiftEffectSummary);
        payload.put(
            transition.opponentGiftEffectsPayloadKey(),
            opponentGiftEffectSummary == null ? new LinkedHashMap<>() : opponentGiftEffectSummary
        );
    }

    private GiftTransitionPreview preparePerformanceStartTransition(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber
    ) {
        matchGiftTriggerService.recordPerformancePhaseSnapshot(matchId, userId, userId, turnNumber);
        if (opponentUserId != null) {
            matchGiftTriggerService.recordPerformancePhaseSnapshot(matchId, userId, opponentUserId, turnNumber);
        }

        List<Map<String, Object>> ownGiftEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnOwnPerformanceStart(
            matchId,
            userId,
            turnNumber
        );
        List<Map<String, Object>> opponentGiftEffects = opponentUserId == null
            ? List.of()
            : matchGiftTriggerService.previewGiftTriggeredEffectsOnOpponentPerformanceStart(
                matchId,
                opponentUserId,
                turnNumber
            );
        return new GiftTransitionPreview(ownGiftEffects, opponentGiftEffects);
    }

    private GiftTransitionPreview preparePerformanceEndTransition(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber
    ) {
        List<Map<String, Object>> ownGiftEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnOwnPerformanceEnd(
            matchId,
            userId,
            turnNumber
        );
        List<Map<String, Object>> opponentGiftEffects = opponentUserId == null
            ? List.of()
            : matchGiftTriggerService.previewGiftTriggeredEffectsOnOpponentPerformanceEnd(
                matchId,
                opponentUserId,
                turnNumber
            );
        return new GiftTransitionPreview(ownGiftEffects, opponentGiftEffects);
    }

    public record AdvancePhaseGiftTransition(
        boolean performanceStart,
        String ownGiftEffectsPayloadKey,
        String opponentGiftEffectsPayloadKey
    ) {
        public static AdvancePhaseGiftTransition forPerformanceStart() {
            return new AdvancePhaseGiftTransition(
                true,
                "performanceStartGiftEffects",
                "opponentPerformanceStartGiftEffects"
            );
        }

        public static AdvancePhaseGiftTransition forPerformanceEnd() {
            return new AdvancePhaseGiftTransition(
                false,
                "performanceEndGiftEffects",
                "opponentPerformanceEndGiftEffects"
            );
        }
    }

    public record GiftTransitionPreview(
        List<Map<String, Object>> ownGiftEffects,
        List<Map<String, Object>> opponentGiftEffects
    ) {
        public static GiftTransitionPreview empty() {
            return new GiftTransitionPreview(List.of(), List.of());
        }
    }
}
