package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.repository.MatchActionRepository;
import java.time.LocalDateTime;

class AttackActionWriterAdapter implements AttackActionLogService.AttackActionWriter {

    private final MatchActionRepository matchActionRepository;

    AttackActionWriterAdapter(MatchActionRepository matchActionRepository) {
        this.matchActionRepository = matchActionRepository;
    }

    @Override
    public AttackActionLogResult appendAction(
        Long matchId,
        Long userId,
        String actionType,
        String payloadJson,
        int turnNumber
    ) {
        MatchActionEntity action = new MatchActionEntity();
        action.setMatchId(matchId);
        action.setUserId(userId);
        action.setActionType(actionType);
        action.setPayload(payloadJson);
        action.setTurnNumber(turnNumber);
        action.setActionOrder(matchActionRepository.findMaxActionOrderByTurn(matchId, turnNumber) + 1);
        action.setExecutedAt(LocalDateTime.now());

        MatchActionEntity saved = matchActionRepository.save(action);
        return new AttackActionLogResult(
            saved.getId(),
            saved.getActionOrder(),
            saved.getActionType(),
            saved.getPayload()
        );
    }
}
