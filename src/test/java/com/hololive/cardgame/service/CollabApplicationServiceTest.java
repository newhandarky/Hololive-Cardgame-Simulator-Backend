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
import org.junit.jupiter.api.Test;

class CollabApplicationServiceTest {

    private final CollabLegacyResolutionBridge bridge = mock(CollabLegacyResolutionBridge.class);
    private final CollabActionValidator validator = mock(CollabActionValidator.class);
    private final CollabActionResolver resolver = mock(CollabActionResolver.class);
    private final CollabApplicationService service = new CollabApplicationService(bridge, validator, resolver);

    @Test
    void validateShouldReturnContextWhenValidatorAllowsAction() {
        CollabAction action = action();
        CollabValidationContext context = validationContext();
        when(bridge.loadValidationContext(action)).thenReturn(context);
        when(validator.validate(action, context)).thenReturn(CollabValidationResult.permitted());

        CollabValidationContext result = service.validate(action);

        assertThat(result).isSameAs(context);
        verify(bridge).loadValidationContext(action);
        verify(validator).validate(action, context);
    }

    @Test
    void validateShouldThrowGameRuleExceptionWhenValidatorBlocksAction() {
        CollabAction action = action();
        CollabValidationContext context = validationContext();
        when(bridge.loadValidationContext(action)).thenReturn(context);
        when(validator.validate(action, context)).thenReturn(
            CollabValidationResult.blocked(GameErrorCode.COLLAB_INVALID_TARGET, "COLLAB 已有 Holomem")
        );

        assertThatThrownBy(() -> service.validate(action))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("COLLAB 已有 Holomem");
    }

    private CollabAction action() {
        return new CollabAction(
            "COLLAB",
            100L,
            10L,
            701L,
            "COLLAB",
            4,
            CollabAction.ActionSource.TEST,
            "trace-collab",
            "idem-collab",
            LocalDateTime.now()
        );
    }

    private CollabValidationContext validationContext() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(4);
        return new CollabValidationContext(
            match,
            10L,
            4,
            10L,
            MatchPhase.MAIN,
            "active",
            "STARTED",
            false,
            false,
            false,
            false,
            0,
            new CollabSourceHolomemSnapshot(901L, 701L, "hBP01-001", "BACK", false)
        );
    }
}
