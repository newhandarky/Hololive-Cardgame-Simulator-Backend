package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hololive.cardgame.model.MatchPhase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdvancePhasePayloadBuilderTest {

    private final AdvancePhasePayloadBuilder builder = new AdvancePhasePayloadBuilder(
        new MatchPhaseAdvanceGiftTransitionService(mock(MatchGiftTriggerService.class)),
        new GiftTriggeredEffectDeferredSummaryBuilder(),
        new FollowupDecisionPayloadAppender()
    );

    @Test
    void buildAdvancePhasePayloadShouldWriteBasePhasePayloadWithoutTransition() {
        Map<String, Object> payload = builder.buildAdvancePhasePayload(
            MatchPhase.MAIN,
            MatchPhase.END,
            null,
            AdvancePhaseFollowup.empty()
        );

        assertThat(payload)
            .containsEntry("fromPhase", "MAIN")
            .containsEntry("toPhase", "END")
            .containsEntry("firstPlayerFirstTurnSkip", true)
            .doesNotContainKey("performanceStartGiftEffects")
            .doesNotContainKey("opponentPerformanceStartGiftEffects")
            .doesNotContainKey("pendingInteractionDecisionId")
            .doesNotContainKey("opponentPendingInteractionDecisionId");
    }

    @Test
    void buildAdvancePhasePayloadShouldAppendPerformanceStartGiftSummariesAndDecisions() {
        Map<String, Object> payload = builder.buildAdvancePhasePayload(
            MatchPhase.MAIN,
            MatchPhase.PERFORMANCE,
            MatchPhaseAdvanceGiftTransitionService.AdvancePhaseGiftTransition.forPerformanceStart(),
            new AdvancePhaseFollowup(
                List.of(giftTrigger("PERFORMANCE_START_SELF", 701L)),
                List.of(giftTrigger("PERFORMANCE_START_OPPONENT", 801L)),
                new FollowupInteractionDecision(901L, "TRIGGER_EFFECT_CONFIRM"),
                new FollowupInteractionDecision(902L, "TRIGGER_EFFECT_CONFIRM")
            )
        );

        assertThat(payload)
            .containsEntry("fromPhase", "MAIN")
            .containsEntry("toPhase", "PERFORMANCE")
            .containsEntry("firstPlayerFirstTurnSkip", false)
            .containsEntry("pendingInteractionDecisionId", 901L)
            .containsEntry("pendingInteractionDecisionType", "TRIGGER_EFFECT_CONFIRM")
            .containsEntry("opponentPendingInteractionDecisionId", 902L)
            .containsEntry("opponentPendingInteractionDecisionType", "TRIGGER_EFFECT_CONFIRM");

        assertThat((Map<String, Object>) payload.get("performanceStartGiftEffects"))
            .containsEntry("sourceActionType", "GIFT")
            .containsEntry("deferred", true)
            .containsEntry("requestedEffects", List.of("DRAW"));
        assertThat((Map<String, Object>) payload.get("opponentPerformanceStartGiftEffects"))
            .containsEntry("sourceActionType", "GIFT")
            .containsEntry("deferred", true)
            .containsEntry("requestedEffects", List.of("HEAL"));
    }

    @Test
    void buildAdvancePhasePayloadShouldAppendPerformanceEndGiftSummaryKeys() {
        Map<String, Object> payload = builder.buildAdvancePhasePayload(
            MatchPhase.PERFORMANCE,
            MatchPhase.END,
            MatchPhaseAdvanceGiftTransitionService.AdvancePhaseGiftTransition.forPerformanceEnd(),
            AdvancePhaseFollowup.empty()
        );

        assertThat(payload)
            .containsEntry("fromPhase", "PERFORMANCE")
            .containsEntry("toPhase", "END")
            .containsEntry("firstPlayerFirstTurnSkip", false)
            .containsKey("performanceEndGiftEffects")
            .containsKey("opponentPerformanceEndGiftEffects")
            .doesNotContainKey("performanceStartGiftEffects")
            .doesNotContainKey("opponentPerformanceStartGiftEffects")
            .doesNotContainKey("pendingInteractionDecisionId")
            .doesNotContainKey("opponentPendingInteractionDecisionId");
    }

    private Map<String, Object> giftTrigger(String triggerType, Long holderCardInstanceId) {
        return Map.of(
            "triggerType",
            triggerType,
            "giftHolderCardInstanceId",
            holderCardInstanceId,
            "requestedEffects",
            triggerType.endsWith("OPPONENT") ? List.of("HEAL") : List.of("DRAW")
        );
    }
}
