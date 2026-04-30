package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.repository.MatchActionRepository;
import java.time.LocalDateTime;
import java.util.Map;

class PlayCardActionLogWriter {

    private final MatchActionRepository matchActionRepository;
    private final MatchPayloadJsonService matchPayloadJsonService;
    private final PlayCardActionLogPayloadBuilder payloadBuilder;

    PlayCardActionLogWriter(
        MatchActionRepository matchActionRepository,
        MatchPayloadJsonService matchPayloadJsonService,
        PlayCardActionLogPayloadBuilder payloadBuilder
    ) {
        this.matchActionRepository = matchActionRepository;
        this.matchPayloadJsonService = matchPayloadJsonService;
        this.payloadBuilder = payloadBuilder;
    }

    MatchActionEntity appendPlayCardAction(
        PlayCardAction action,
        PlayCardResolutionResult resolutionResult,
        PlayCardEffectResolution effectResolution
    ) {
        Map<String, Object> payload = payloadBuilder.buildPayload(action, resolutionResult, effectResolution);
        Long matchId = resolutionResult.match().getId();
        int turnNumber = resolutionResult.turnNumber();

        MatchActionEntity actionLog = new MatchActionEntity();
        actionLog.setMatchId(matchId);
        actionLog.setUserId(resolutionResult.actorUserId());
        actionLog.setActionType(payloadBuilder.resolveLegacyActionType(resolutionResult));
        actionLog.setPayload(matchPayloadJsonService.toJson(payload));
        actionLog.setTurnNumber(turnNumber);
        actionLog.setActionOrder(matchActionRepository.findMaxActionOrderByTurn(matchId, turnNumber) + 1);
        actionLog.setExecutedAt(LocalDateTime.now());
        return matchActionRepository.save(actionLog);
    }
}
