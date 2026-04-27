package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class EndTurnActionValidatorTest {

    private final EndTurnActionValidator validator = new EndTurnActionValidator();

    @Test
    void validateShouldReturnStaleBeforeTurnOwnershipAndPhaseChecks() {
        EndTurnAction action = new EndTurnAction(
            "END_TURN",
            100L,
            10L,
            3,
            EndTurnAction.ActionSource.TEST,
            "trace-stale",
            "idempotency-stale",
            LocalDateTime.now()
        );
        EndTurnValidationContext context = baseContext(4, 99L, MatchPhase.MAIN, false);

        EndTurnValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.STALE_ACTION);
    }

    @Test
    void validateShouldReturnDuplicateBeforeTurnOwnershipAndPhaseChecksWhenTurnMatches() {
        EndTurnAction action = new EndTurnAction(
            "END_TURN",
            100L,
            10L,
            4,
            EndTurnAction.ActionSource.TEST,
            "trace-duplicate",
            "idempotency-duplicate",
            LocalDateTime.now()
        );
        EndTurnValidationContext context = baseContext(4, 99L, MatchPhase.MAIN, true);

        EndTurnValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.DUPLICATE_ACTION);
    }

    private EndTurnValidationContext baseContext(
        int currentTurnNumber,
        Long currentTurnPlayerId,
        MatchPhase currentPhase,
        boolean duplicateAction
    ) {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentTurnPlayerId(currentTurnPlayerId);
        match.setCurrentPhase(currentPhase.name());
        match.setTurnNumber(currentTurnNumber);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");

        return new EndTurnValidationContext(
            match,
            10L,
            20L,
            currentTurnNumber,
            currentTurnPlayerId,
            currentPhase,
            "active",
            "STARTED",
            duplicateAction,
            false,
            false,
            new EndTurnRequiredActionSummary(true, false, false, List.of())
        );
    }
}
