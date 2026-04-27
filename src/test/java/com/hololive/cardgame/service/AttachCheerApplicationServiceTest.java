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

class AttachCheerApplicationServiceTest {

    private final AttachCheerLegacyResolutionBridge bridge = mock(AttachCheerLegacyResolutionBridge.class);
    private final AttachCheerActionValidator validator = mock(AttachCheerActionValidator.class);
    private final AttachCheerActionResolver resolver = mock(AttachCheerActionResolver.class);
    private final AttachCheerEventFactory eventFactory = mock(AttachCheerEventFactory.class);
    private final AttachCheerTriggerDispatcher triggerDispatcher = mock(AttachCheerTriggerDispatcher.class);
    private final AttachCheerApplicationService service = new AttachCheerApplicationService(
        bridge,
        validator,
        resolver,
        eventFactory,
        triggerDispatcher
    );

    @Test
    void validateShouldReturnContextWhenValidatorAllowsAction() {
        AttachCheerAction action = action();
        AttachCheerValidationContext context = validationContext();
        when(bridge.loadValidationContext(action)).thenReturn(context);
        when(validator.validate(action, context)).thenReturn(AttachCheerValidationResult.permitted());

        AttachCheerValidationContext result = service.validate(action);

        assertThat(result).isSameAs(context);
        verify(bridge).loadValidationContext(action);
        verify(validator).validate(action, context);
    }

    @Test
    void validateShouldThrowGameRuleExceptionWhenValidatorBlocksAction() {
        AttachCheerAction action = action();
        AttachCheerValidationContext context = validationContext();
        when(bridge.loadValidationContext(action)).thenReturn(context);
        when(validator.validate(action, context)).thenReturn(
            AttachCheerValidationResult.blocked(GameErrorCode.ATTACH_CHEER_INVALID_TARGET, "指定卡片不是 Cheer 卡")
        );

        assertThatThrownBy(() -> service.validate(action))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("指定卡片不是 Cheer 卡");
    }

    private AttachCheerAction action() {
        return new AttachCheerAction(
            "ATTACH_CHEER",
            100L,
            10L,
            701L,
            801L,
            4,
            AttachCheerAction.ActionSource.TEST,
            "trace-attach-cheer",
            "idem-attach-cheer",
            LocalDateTime.now()
        );
    }

    private AttachCheerValidationContext validationContext() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(4);
        return new AttachCheerValidationContext(
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
            new AttachCheerSourceCardSnapshot(701L, "hY01-001", "HAND", true),
            new AttachCheerTargetHolomemSnapshot(901L, 801L, "hBP01-001", "CENTER")
        );
    }
}
