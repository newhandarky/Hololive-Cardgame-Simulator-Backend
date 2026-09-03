package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DrawTurnCommandHandlerTest {

    @Mock
    private DrawTurnApplicationService drawTurnApplicationService;

    @InjectMocks
    private DrawTurnCommandHandler handler;

    @Test
    void handleShouldDelegateTypedContextAndReturnEvent() {
        MatchCommandResult result = handler.handle(new DrawTurnCommand(101L, 202L));

        verify(drawTurnApplicationService).execute(101L, 202L);
        assertThat(result).isEqualTo(new MatchCommandResult(101L, "DRAW_TURN"));
    }
}
