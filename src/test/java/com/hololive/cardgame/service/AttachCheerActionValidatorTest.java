package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AttachCheerActionValidatorTest {

    private final AttachCheerActionValidator validator = new AttachCheerActionValidator();

    @Test
    void validateShouldReturnStaleBeforeTurnOwnershipAndPhaseChecks() {
        AttachCheerAction action = action(3);
        AttachCheerValidationContext context = baseContext(
            4,
            99L,
            MatchPhase.RESET,
            false,
            false,
            false,
            sourceCheer("HAND", true),
            targetHolomem()
        );

        AttachCheerValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.STALE_ACTION);
    }

    @Test
    void validateShouldBlockSourceCardOutsideAllowedZones() {
        AttachCheerAction action = action(4);
        AttachCheerValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            sourceCheer("ARCHIVE", true),
            targetHolomem()
        );

        AttachCheerValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.ATTACH_CHEER_INVALID_TARGET);
        assertThat(result.message()).contains("HAND 或 CHEER_DECK");
    }

    @Test
    void validateShouldBlockNonCheerSourceCard() {
        AttachCheerAction action = action(4);
        AttachCheerValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            sourceCheer("HAND", false),
            targetHolomem()
        );

        AttachCheerValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.ATTACH_CHEER_INVALID_TARGET);
        assertThat(result.message()).contains("不是 Cheer");
    }

    @Test
    void validateShouldBlockMissingTargetHolomem() {
        AttachCheerAction action = action(4);
        AttachCheerValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            sourceCheer("CHEER_DECK", true),
            null
        );

        AttachCheerValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.NOT_FOUND);
        assertThat(result.message()).contains("Holomem");
    }

    @Test
    void validateShouldPermitCheerFromHandToOwnHolomem() {
        AttachCheerAction action = action(4);
        AttachCheerValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            sourceCheer("HAND", true),
            targetHolomem()
        );

        AttachCheerValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    private AttachCheerAction action(int requestedTurnNumber) {
        return new AttachCheerAction(
            "ATTACH_CHEER",
            100L,
            10L,
            701L,
            801L,
            requestedTurnNumber,
            AttachCheerAction.ActionSource.TEST,
            "trace-attach-cheer",
            "idem-attach-cheer",
            LocalDateTime.now()
        );
    }

    private AttachCheerValidationContext baseContext(
        int currentTurnNumber,
        Long currentTurnPlayerId,
        MatchPhase currentPhase,
        boolean duplicateAction,
        boolean actorPendingInteractions,
        boolean stageActionLocked,
        AttachCheerSourceCardSnapshot sourceCard,
        AttachCheerTargetHolomemSnapshot targetHolomem
    ) {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentTurnPlayerId(currentTurnPlayerId);
        match.setCurrentPhase(currentPhase.name());
        match.setTurnNumber(currentTurnNumber);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");

        return new AttachCheerValidationContext(
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
            sourceCard,
            targetHolomem
        );
    }

    private AttachCheerSourceCardSnapshot sourceCheer(String zone, boolean cheerCard) {
        return new AttachCheerSourceCardSnapshot(701L, "hY01-001", zone, cheerCard);
    }

    private AttachCheerTargetHolomemSnapshot targetHolomem() {
        return new AttachCheerTargetHolomemSnapshot(901L, 801L, "hBP01-001", "CENTER");
    }
}
