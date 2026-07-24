package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 對戰 command 的明確 application entry point。
 */
@Service
public class MatchCommandGateway {

    private final Map<Class<? extends MatchCommand>, MatchCommandHandler<? extends MatchCommand>> handlers;

    public MatchCommandGateway(List<MatchCommandHandler<? extends MatchCommand>> handlers) {
        Map<Class<? extends MatchCommand>, MatchCommandHandler<? extends MatchCommand>> registeredHandlers =
            new LinkedHashMap<>();
        for (MatchCommandHandler<? extends MatchCommand> handler : handlers) {
            MatchCommandHandler<? extends MatchCommand> requiredHandler = Objects.requireNonNull(handler, "handler 不可為 null");
            Class<? extends MatchCommand> commandType = Objects.requireNonNull(
                requiredHandler.commandType(),
                "commandType 不可為 null"
            );
            if (registeredHandlers.putIfAbsent(commandType, requiredHandler) != null) {
                throw new IllegalStateException("重複註冊對戰指令 handler: " + commandType.getSimpleName());
            }
        }
        this.handlers = Map.copyOf(registeredHandlers);
    }

    public MatchCommandResult submit(MatchCommand command) {
        MatchCommand requiredCommand = Objects.requireNonNull(command, "command 不可為 null");
        MatchCommandHandler<? extends MatchCommand> handler = handlers.get(requiredCommand.getClass());
        if (handler == null) {
            throw new IllegalStateException("找不到對戰指令 handler: " + requiredCommand.getClass().getSimpleName());
        }
        return handler.dispatch(requiredCommand);
    }
}
