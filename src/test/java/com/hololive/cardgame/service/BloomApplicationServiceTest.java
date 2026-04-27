package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BloomApplicationServiceTest {

    private final BloomLegacyResolutionBridge bridge = mock(BloomLegacyResolutionBridge.class);
    private final BloomActionValidator validator = mock(BloomActionValidator.class);
    private final BloomActionResolver resolver = mock(BloomActionResolver.class);
    private final BloomEventFactory eventFactory = mock(BloomEventFactory.class);
    private final BloomTriggerDispatcher triggerDispatcher = mock(BloomTriggerDispatcher.class);
    private final BloomApplicationService service = new BloomApplicationService(
        bridge,
        validator,
        resolver,
        eventFactory,
        triggerDispatcher
    );

    @Test
    void validateShouldReturnContextWhenValidatorAllowsAction() {
        BloomAction action = action();
        BloomValidationContext context = validationContext();
        when(bridge.loadValidationContext(action)).thenReturn(context);
        when(validator.validate(action, context)).thenReturn(BloomValidationResult.permitted());

        BloomValidationContext result = service.validate(action);

        assertThat(result).isSameAs(context);
        verify(bridge).loadValidationContext(action);
        verify(validator).validate(action, context);
    }

    @Test
    void validateShouldThrowGameRuleExceptionWhenValidatorBlocksAction() {
        BloomAction action = action();
        BloomValidationContext context = validationContext();
        when(bridge.loadValidationContext(action)).thenReturn(context);
        when(validator.validate(action, context)).thenReturn(
            BloomValidationResult.blocked(GameErrorCode.BLOOM_INVALID_TARGET, "BLOOM 需要與目標 Holomem 同名")
        );

        assertThatThrownBy(() -> service.validate(action))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("BLOOM 需要與目標 Holomem 同名");
    }

    private BloomAction action() {
        return new BloomAction(
            "BLOOM",
            100L,
            10L,
            701L,
            801L,
            4,
            BloomAction.ActionSource.TEST,
            "trace-bloom",
            "idem-bloom",
            LocalDateTime.now()
        );
    }

    private BloomValidationContext validationContext() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(4);
        return new BloomValidationContext(
            match,
            10L,
            4,
            10L,
            MatchPhase.MAIN,
            "active",
            "STARTED",
            false,
            false,
            new BloomSourceCardSnapshot(701L, "hBP01-002", "Tokino Sora", "FIRST", 80, "HAND", true),
            new BloomTargetSnapshot(
                901L,
                801L,
                "hBP01-001",
                "Tokino Sora",
                "DEBUT",
                "CENTER",
                0,
                1,
                null,
                false,
                null,
                false
            )
        );
    }
}
