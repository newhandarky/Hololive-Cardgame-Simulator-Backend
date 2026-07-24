package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MatchCommandGatewayTest {

    @Test
    void submitShouldDispatchToRegisteredTypedHandler() {
        RecordingConcedeHandler handler = new RecordingConcedeHandler();
        MatchCommandGateway gateway = new MatchCommandGateway(List.of(handler));
        ConcedeMatchCommand command = new ConcedeMatchCommand(101L, 201L);

        MatchCommandResult result = gateway.submit(command);

        assertThat(handler.handledCommand).isEqualTo(command);
        assertThat(result).isEqualTo(new MatchCommandResult(101L, "CONCEDE"));
    }

    @Test
    void submitShouldFailFastWhenCommandHasNoRegisteredHandler() {
        MatchCommandGateway gateway = new MatchCommandGateway(List.of());

        assertThatThrownBy(() -> gateway.submit(new ConcedeMatchCommand(101L, 201L)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("找不到對戰指令 handler: ConcedeMatchCommand");
    }

    @Test
    void constructorShouldRejectDuplicateCommandHandlers() {
        assertThatThrownBy(() -> new MatchCommandGateway(List.of(new RecordingConcedeHandler(), new RecordingConcedeHandler())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("重複註冊對戰指令 handler: ConcedeMatchCommand");
    }

    @Test
    void constructorShouldRejectHandlerWithoutCommandType() {
        assertThatThrownBy(() -> new MatchCommandGateway(List.of(new NullCommandTypeHandler())))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("commandType 不可為 null");
    }

    private static class RecordingConcedeHandler implements MatchCommandHandler<ConcedeMatchCommand> {

        private ConcedeMatchCommand handledCommand;

        @Override
        public Class<ConcedeMatchCommand> commandType() {
            return ConcedeMatchCommand.class;
        }

        @Override
        public MatchCommandResult handle(ConcedeMatchCommand command) {
            handledCommand = command;
            return new MatchCommandResult(command.matchId(), "CONCEDE");
        }
    }

    private static class NullCommandTypeHandler implements MatchCommandHandler<ConcedeMatchCommand> {

        @Override
        public Class<ConcedeMatchCommand> commandType() {
            return null;
        }

        @Override
        public MatchCommandResult handle(ConcedeMatchCommand command) {
            throw new UnsupportedOperationException("不應執行 handle");
        }
    }
}
