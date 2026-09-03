package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

/**
 * 將 DRAW_TURN command 路由到獨立 application service。
 */
@Service
public class DrawTurnCommandHandler implements MatchCommandHandler<DrawTurnCommand> {

    private final DrawTurnApplicationService drawTurnApplicationService;

    public DrawTurnCommandHandler(DrawTurnApplicationService drawTurnApplicationService) {
        this.drawTurnApplicationService = drawTurnApplicationService;
    }

    @Override
    public Class<DrawTurnCommand> commandType() {
        return DrawTurnCommand.class;
    }

    @Override
    public MatchCommandResult handle(DrawTurnCommand command) {
        MatchCommandContext context = command.context();
        drawTurnApplicationService.execute(context.matchId(), context.actorUserId());
        return new MatchCommandResult(context.matchId(), "DRAW_TURN");
    }
}
