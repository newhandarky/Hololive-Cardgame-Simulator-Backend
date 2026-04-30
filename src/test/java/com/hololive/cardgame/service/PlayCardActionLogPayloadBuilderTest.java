package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayCardActionLogPayloadBuilderTest {

    private final PlayCardActionLogPayloadBuilder builder = new PlayCardActionLogPayloadBuilder();

    @Test
    void buildPayloadShouldKeepOpeningSetupFieldsOnly() {
        PlayCardAction action = action(true);
        PlayCardResolutionResult resolutionResult = resolutionResult("CENTER", true, true);
        PlayCardEffectResolution effectResolution = new PlayCardEffectResolution(
            Map.of("triggered", false),
            List.of(),
            Map.of("gift", "ignored"),
            901L,
            "TRIGGER_EFFECT_CONFIRM",
            true,
            List.of(Map.of("order", 1))
        );

        Map<String, Object> payload = builder.buildPayload(action, resolutionResult, effectResolution);

        assertThat(payload)
            .containsEntry("cardInstanceId", 501L)
            .containsEntry("cardId", "hBP01-001")
            .containsEntry("targetZone", "CENTER")
            .containsEntry("enteredTurn", 3)
            .containsEntry("faceDown", true)
            .containsEntry("idempotencyKey", action.idempotencyKey())
            .containsEntry("triggerSummary", Map.of("triggered", false))
            .doesNotContainKeys(
                "giftEffect",
                "triggerResolutionOrder",
                "pendingInteractionDecisionId",
                "pendingInteractionDecisionType"
            );
    }

    @Test
    void buildPayloadShouldKeepMainPhaseFollowupFields() {
        PlayCardAction action = action(false);
        PlayCardResolutionResult resolutionResult = resolutionResult("BACK", false, false);
        Map<String, Object> giftEffect = Map.of("effectType", "GIFT_TRIGGER");
        List<Map<String, Object>> triggerResolutionOrder = List.of(Map.of("source", "GIFT"));
        PlayCardEffectResolution effectResolution = new PlayCardEffectResolution(
            Map.of("triggered", true),
            List.of(Map.of("cardInstanceId", 701L)),
            giftEffect,
            901L,
            "TRIGGER_EFFECT_CONFIRM",
            false,
            triggerResolutionOrder
        );

        Map<String, Object> payload = builder.buildPayload(action, resolutionResult, effectResolution);

        assertThat(payload)
            .containsEntry("cardInstanceId", 501L)
            .containsEntry("cardId", "hBP01-001")
            .containsEntry("targetZone", "BACK")
            .containsEntry("enteredTurn", 3)
            .containsEntry("faceDown", false)
            .containsEntry("idempotencyKey", action.idempotencyKey())
            .containsEntry("triggerSummary", Map.of("triggered", true))
            .containsEntry("giftEffect", giftEffect)
            .containsEntry("triggerResolutionOrder", triggerResolutionOrder)
            .containsEntry("pendingInteractionDecisionId", 901L)
            .containsEntry("pendingInteractionDecisionType", "TRIGGER_EFFECT_CONFIRM");
    }

    @Test
    void resolveLegacyActionTypeShouldKeepCompatibilityTypes() {
        assertThat(builder.resolveLegacyActionType(resolutionResult("CENTER", true, true)))
            .isEqualTo("OPENING_SET_CENTER");
        assertThat(builder.resolveLegacyActionType(resolutionResult("BACK", true, true)))
            .isEqualTo("OPENING_SET_BACK");
        assertThat(builder.resolveLegacyActionType(resolutionResult("BACK", false, false)))
            .isEqualTo("PLAY_TO_STAGE");
    }

    private PlayCardAction action(boolean openingReset) {
        return PlayCardAction.fromApi(
            101L,
            201L,
            501L,
            openingReset ? "CENTER" : "BACK",
            3,
            openingReset,
            "idem-501"
        );
    }

    private PlayCardResolutionResult resolutionResult(String targetZone, boolean faceDown, boolean openingReset) {
        return new PlayCardResolutionResult(
            new MatchEntity(),
            201L,
            3,
            501L,
            "hBP01-001",
            "HAND",
            targetZone,
            601L,
            3,
            faceDown,
            "DEBUT",
            openingReset
        );
    }
}
