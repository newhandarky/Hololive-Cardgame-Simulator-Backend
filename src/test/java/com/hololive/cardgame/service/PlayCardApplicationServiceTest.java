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

class PlayCardApplicationServiceTest {

    private final PlayCardLegacyResolutionBridge bridge = mock(PlayCardLegacyResolutionBridge.class);
    private final PlayCardActionValidator validator = mock(PlayCardActionValidator.class);
    private final PlayCardActionResolver resolver = mock(PlayCardActionResolver.class);
    private final PlayCardEventFactory eventFactory = mock(PlayCardEventFactory.class);
    private final PlayCardTriggerDispatcher triggerDispatcher = mock(PlayCardTriggerDispatcher.class);
    private final PlayCardApplicationService service = new PlayCardApplicationService(
        bridge,
        validator,
        resolver,
        eventFactory,
        triggerDispatcher
    );

    @Test
    void validateShouldReturnContextWhenPermitted() {
        PlayCardAction action = action();
        PlayCardValidationContext context = validationContext();
        when(bridge.loadValidationContext(action)).thenReturn(context);
        when(validator.validate(action, context)).thenReturn(PlayCardValidationResult.permitted());

        PlayCardValidationContext result = service.validate(action);

        assertThat(result).isSameAs(context);
        verify(bridge).loadValidationContext(action);
        verify(validator).validate(action, context);
    }

    @Test
    void validateShouldThrowGameRuleExceptionWhenRejected() {
        PlayCardAction action = action();
        PlayCardValidationContext context = validationContext();
        when(bridge.loadValidationContext(action)).thenReturn(context);
        when(validator.validate(action, context)).thenReturn(
            PlayCardValidationResult.blocked(GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED, "請改用 BLOOM")
        );

        assertThatThrownBy(() -> service.validate(action))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("請改用 BLOOM");
    }

    private PlayCardAction action() {
        return new PlayCardAction(
            "PLAY_CARD",
            100L,
            10L,
            701L,
            "BACK",
            4,
            false,
            PlayCardAction.ActionSource.TEST,
            "trace-play-card",
            "idem-play-card",
            LocalDateTime.now()
        );
    }

    private PlayCardValidationContext validationContext() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(4);
        return new PlayCardValidationContext(
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
            true,
            true,
            0,
            new PlayCardSourceCardSnapshot(701L, "hBP01-001", "HAND", true, "DEBUT")
        );
    }
}
