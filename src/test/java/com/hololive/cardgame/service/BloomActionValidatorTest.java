package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class BloomActionValidatorTest {

    private final BloomActionValidator validator = new BloomActionValidator();

    @Test
    void validateShouldReturnStaleBeforeTurnOwnershipAndPhaseChecks() {
        BloomAction action = action(3);
        BloomValidationContext context = baseContext(4, 99L, MatchPhase.RESET, false, sourceCard(), target());

        BloomValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.STALE_ACTION);
    }

    @Test
    void validateShouldReturnDuplicateBeforeTurnOwnershipAndPhaseChecksWhenTurnMatches() {
        BloomAction action = action(4);
        BloomValidationContext context = baseContext(4, 99L, MatchPhase.RESET, true, sourceCard(), target());

        BloomValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.DUPLICATE_ACTION);
    }

    @Test
    void validateShouldBlockSecondBloomWithoutExtraAllowance() {
        BloomAction action = action(4);
        BloomTargetSnapshot alreadyBloomedTarget = new BloomTargetSnapshot(
            901L,
            801L,
            "hBP01-001",
            "Tokino Sora",
            "DEBUT",
            "CENTER",
            10,
            1,
            4,
            false,
            null,
            false
        );
        BloomValidationContext context = baseContext(
            4,
            10L,
            MatchPhase.MAIN,
            false,
            sourceCard(),
            alreadyBloomedTarget
        );

        BloomValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(GameErrorCode.BLOOM_INVALID_TARGET);
        assertThat(result.message()).contains("已執行過 BLOOM");
    }

    @Test
    void validateShouldPermitSequentialBloomWithDamageCarryWithinHp() {
        BloomAction action = action(4);
        BloomValidationContext context = baseContext(4, 10L, MatchPhase.MAIN, false, sourceCard(), target());

        BloomValidationResult result = validator.validate(action, context);

        assertThat(result.allowed()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    private BloomAction action(int requestedTurnNumber) {
        return new BloomAction(
            "BLOOM",
            100L,
            10L,
            701L,
            801L,
            requestedTurnNumber,
            BloomAction.ActionSource.TEST,
            "trace-bloom",
            "idem-bloom",
            LocalDateTime.now()
        );
    }

    private BloomValidationContext baseContext(
        int currentTurnNumber,
        Long currentTurnPlayerId,
        MatchPhase currentPhase,
        boolean duplicateAction,
        BloomSourceCardSnapshot sourceCard,
        BloomTargetSnapshot target
    ) {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentTurnPlayerId(currentTurnPlayerId);
        match.setCurrentPhase(currentPhase.name());
        match.setTurnNumber(currentTurnNumber);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");

        return new BloomValidationContext(
            match,
            10L,
            currentTurnNumber,
            currentTurnPlayerId,
            currentPhase,
            "active",
            "STARTED",
            duplicateAction,
            false,
            sourceCard,
            target
        );
    }

    private BloomSourceCardSnapshot sourceCard() {
        return new BloomSourceCardSnapshot(
            701L,
            "hBP01-002",
            "Tokino Sora",
            "FIRST",
            80,
            "HAND",
            true
        );
    }

    private BloomTargetSnapshot target() {
        return new BloomTargetSnapshot(
            901L,
            801L,
            "hBP01-001",
            "Tokino Sora",
            "DEBUT",
            "CENTER",
            10,
            1,
            null,
            false,
            null,
            false
        );
    }
}
