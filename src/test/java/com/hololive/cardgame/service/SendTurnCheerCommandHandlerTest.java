package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SendTurnCheerCommandHandlerTest {

    @Mock
    private SendTurnCheerApplicationService sendTurnCheerApplicationService;

    @InjectMocks
    private SendTurnCheerCommandHandler handler;

    @Test
    void handleShouldDelegateTypedContextAndReturnEvent() {
        MatchCommandResult result = handler.handle(new SendTurnCheerCommand(101L, 202L));

        verify(sendTurnCheerApplicationService).execute(101L, 202L);
        assertThat(result).isEqualTo(new MatchCommandResult(101L, "TURN_CHEER"));
    }
}
