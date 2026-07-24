package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

/**
 * 在對戰規則尚未拆分前，將投降 command 委派給既有 service。
 */
@Service
public class LegacyConcedeCommandHandler implements MatchCommandHandler<ConcedeMatchCommand> {

    private final MatchActionService matchActionService;

    public LegacyConcedeCommandHandler(MatchActionService matchActionService) {
        this.matchActionService = matchActionService;
    }

    @Override
    public Class<ConcedeMatchCommand> commandType() {
        return ConcedeMatchCommand.class;
    }

    @Override
    public MatchCommandResult handle(ConcedeMatchCommand command) {
        MatchCommandContext context = command.context();
        matchActionService.concede(context.matchId(), context.actorUserId());
        return new MatchCommandResult(context.matchId(), "CONCEDE");
    }
}
