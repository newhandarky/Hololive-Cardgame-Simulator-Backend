package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class LegacyConcedeCommandHandlerTest {

    @Test
    void handleShouldDelegateToLegacyConcedeServiceAndReturnPublishContext() {
        MatchActionService matchActionService = mock(MatchActionService.class);
        LegacyConcedeCommandHandler handler = new LegacyConcedeCommandHandler(matchActionService);

        MatchCommandResult result = handler.handle(new ConcedeMatchCommand(101L, 201L));

        verify(matchActionService).concede(101L, 201L);
        assertThat(result).isEqualTo(new MatchCommandResult(101L, "CONCEDE"));
    }
}
