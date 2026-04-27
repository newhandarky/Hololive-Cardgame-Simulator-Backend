package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PlayCardActionValidatorTest {

    private final PlayCardActionValidator validator = new PlayCardActionValidator();

    @Test
    void validateShouldReturnStaleBeforeTurnOwnershipAndPhaseChecks() {
        PlayCardAction action = action(3, "BACK", false);
        PlayCardValidationContext context = baseContext(
            4,
            99L,
            MatchPhase.PERFORMANCE,
            false,
            false,
            false,
            true,
            true,
            0,
            sourceMember("HAND", "DEBUT", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.STALE_ACTION);
    }

    @Test
    void validateShouldPermitOpeningDebutToCenterBeforeOpeningCenterPlaced() {
        PlayCardAction action = action(4, "CENTER", true);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.RESET,
            false,
            false,
            false,
            true,
            false,
            0,
            sourceMember("HAND", "DEBUT", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void validateShouldBlockOpeningBackBeforeCenterPlaced() {
        PlayCardAction action = action(4, "BACK", true);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.RESET,
            false,
            false,
            false,
            true,
            false,
            0,
            sourceMember("HAND", "DEBUT", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.CONFLICT);
        assertThat(result.message()).contains("CENTER 前");
    }

    @Test
    void validateShouldPermitOpeningSpotToBackAfterOpeningCenterPlaced() {
        PlayCardAction action = action(4, "BACK", true);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.RESET,
            false,
            false,
            false,
            true,
            true,
            0,
            sourceMember("HAND", "SPOT", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void validateShouldBlockMainPhaseCenterPlacement() {
        PlayCardAction action = action(4, "CENTER", false);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            true,
            true,
            0,
            sourceMember("HAND", "DEBUT", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.CONFLICT);
        assertThat(result.message()).contains("BACK");
    }

    @Test
    void validateShouldBlockFirstSecondBuzzFromHandInMainPhase() {
        PlayCardAction action = action(4, "BACK", false);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            true,
            true,
            0,
            sourceMember("HAND", "FIRST", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED);
        assertThat(result.message()).contains("BLOOM");
    }

    @Test
    void validateShouldBlockSourceCardOutsideHand() {
        PlayCardAction action = action(4, "BACK", false);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            true,
            true,
            0,
            sourceMember("ARCHIVE", "DEBUT", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.CONFLICT);
        assertThat(result.message()).contains("手牌");
    }

    @Test
    void validateShouldBlockNonMemberSourceCard() {
        PlayCardAction action = action(4, "BACK", false);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            true,
            true,
            0,
            sourceMember("HAND", "", false)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.CONFLICT);
        assertThat(result.message()).contains("MEMBER");
    }

    @Test
    void validateShouldBlockBackWhenFull() {
        PlayCardAction action = action(4, "BACK", false);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            true,
            true,
            5,
            sourceMember("HAND", "DEBUT", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.CONFLICT);
        assertThat(result.message()).contains("BACK 已滿");
    }

    @Test
    void validateShouldPermitMainDebutToBack() {
        PlayCardAction action = action(4, "BACK", false);
        PlayCardValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            true,
            true,
            4,
            sourceMember("HAND", "DEBUT", true)
        );

        PlayCardValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    private PlayCardAction action(int requestedTurnNumber, String targetZone, boolean openingReset) {
        return new PlayCardAction(
            "PLAY_CARD",
            100L,
            10L,
            701L,
            targetZone,
            requestedTurnNumber,
            openingReset,
            PlayCardAction.ActionSource.TEST,
            "trace-play-card",
            "idem-play-card",
            LocalDateTime.now()
        );
    }

    private PlayCardValidationContext baseContext(
        int currentTurnNumber,
        Long currentTurnPlayerId,
        MatchPhase currentPhase,
        boolean duplicateAction,
        boolean actorPendingInteractions,
        boolean stageActionLocked,
        boolean actorMulliganDone,
        boolean openingCenterPlaced,
        int targetZoneOccupiedCount,
        PlayCardSourceCardSnapshot sourceCard
    ) {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentTurnPlayerId(currentTurnPlayerId);
        match.setCurrentPhase(currentPhase.name());
        match.setTurnNumber(currentTurnNumber);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");

        return new PlayCardValidationContext(
            match,
            10L,
            currentTurnNumber,
            currentTurnPlayerId,
            currentPhase,
            "active",
            "STARTED",
            duplicateAction,
            actorPendingInteractions,
            stageActionLocked,
            actorMulliganDone,
            openingCenterPlaced,
            targetZoneOccupiedCount,
            sourceCard
        );
    }

    private PlayCardSourceCardSnapshot sourceMember(String zone, String levelType, boolean memberCard) {
        return new PlayCardSourceCardSnapshot(701L, "hBP01-001", zone, memberCard, levelType);
    }
}
