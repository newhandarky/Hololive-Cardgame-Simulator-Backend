package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

/**
 * 將 SEND_TURN_CHEER command 路由到獨立 application service。
 */
@Service
public class SendTurnCheerCommandHandler implements MatchCommandHandler<SendTurnCheerCommand> {

    private final SendTurnCheerApplicationService sendTurnCheerApplicationService;

    public SendTurnCheerCommandHandler(SendTurnCheerApplicationService sendTurnCheerApplicationService) {
        this.sendTurnCheerApplicationService = sendTurnCheerApplicationService;
    }

    @Override
    public Class<SendTurnCheerCommand> commandType() {
        return SendTurnCheerCommand.class;
    }

    @Override
    public MatchCommandResult handle(SendTurnCheerCommand command) {
        MatchCommandContext context = command.context();
        sendTurnCheerApplicationService.execute(context.matchId(), context.actorUserId());
        return new MatchCommandResult(context.matchId(), "TURN_CHEER");
    }
}
