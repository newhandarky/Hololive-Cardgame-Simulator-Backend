package com.hololive.cardgame.service;

/**
 * 處理單一 typed match command 的 application handler。
 */
public interface MatchCommandHandler<C extends MatchCommand> {

    Class<C> commandType();

    MatchCommandResult handle(C command);

    default MatchCommandResult dispatch(MatchCommand command) {
        return handle(commandType().cast(command));
    }
}
