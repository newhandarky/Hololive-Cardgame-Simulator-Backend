package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CollabActionValidatorTest {

    private final CollabActionValidator validator = new CollabActionValidator();

    @Test
    void validateShouldReturnStaleBeforeTurnOwnershipAndPhaseChecks() {
        CollabAction action = action(3);
        CollabValidationContext context = baseContext(4, 99L, MatchPhase.RESET, false, false, false, 0, sourceHolomem());

        CollabValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.STALE_ACTION);
    }

    @Test
    void validateShouldBlockRestedSourceHolomem() {
        CollabAction action = action(4);
        CollabValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            false,
            false,
            0,
            new CollabSourceHolomemSnapshot(901L, 701L, "hBP01-001", "BACK", true)
        );

        CollabValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.COLLAB_INVALID_TARGET);
        assertThat(result.message()).contains("休息中的 Holomem");
    }

    @Test
    void validateShouldPermitBackHolomemWhenCollabZoneIsEmpty() {
        CollabAction action = action(4);
        CollabValidationContext context = baseContext(4, 10L, MatchPhase.MAIN, false, false, false, 0, sourceHolomem());

        CollabValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    private CollabAction action(int requestedTurnNumber) {
        return new CollabAction(
            "COLLAB",
            100L,
            10L,
            701L,
            "COLLAB",
            requestedTurnNumber,
            CollabAction.ActionSource.TEST,
            "trace-collab",
            "idem-collab",
            LocalDateTime.now()
        );
    }

    private CollabValidationContext baseContext(
        int currentTurnNumber,
        Long currentTurnPlayerId,
        MatchPhase currentPhase,
        boolean duplicateAction,
        boolean stageActionLocked,
        boolean collabUsedThisTurn,
        int targetZoneOccupiedCount,
        CollabSourceHolomemSnapshot sourceHolomem
    ) {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentTurnPlayerId(currentTurnPlayerId);
        match.setCurrentPhase(currentPhase.name());
        match.setTurnNumber(currentTurnNumber);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");

        return new CollabValidationContext(
            match,
            10L,
            currentTurnNumber,
            currentTurnPlayerId,
            currentPhase,
            "active",
            "STARTED",
            duplicateAction,
            false,
            stageActionLocked,
            collabUsedThisTurn,
            targetZoneOccupiedCount,
            sourceHolomem
        );
    }

    private CollabSourceHolomemSnapshot sourceHolomem() {
        return new CollabSourceHolomemSnapshot(901L, 701L, "hBP01-001", "BACK", false);
    }
}
