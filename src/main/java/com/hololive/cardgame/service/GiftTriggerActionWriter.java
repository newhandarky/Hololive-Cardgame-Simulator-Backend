package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.repository.MatchActionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class GiftTriggerActionWriter {

    static final String ACTION_TYPE_GIFT_TRIGGER = "GIFT_TRIGGER";

    private final MatchActionRepository matchActionRepository;
    private final MatchPayloadJsonService matchPayloadJsonService;

    GiftTriggerActionWriter(
        MatchActionRepository matchActionRepository,
        MatchPayloadJsonService matchPayloadJsonService
    ) {
        this.matchActionRepository = matchActionRepository;
        this.matchPayloadJsonService = matchPayloadJsonService;
    }

    List<MatchActionEntity> appendGiftTriggerActions(
        Long matchId,
        Long userId,
        int turnNumber,
        List<Map<String, Object>> payloads
    ) {
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<MatchActionEntity> savedActions = new ArrayList<>();
        int nextActionOrder = matchActionRepository.findMaxActionOrderByTurn(matchId, turnNumber) + 1;
        for (Map<String, Object> payload : payloads) {
            savedActions.add(appendGiftTriggerAction(matchId, userId, turnNumber, payload, nextActionOrder));
            nextActionOrder++;
        }
        return savedActions;
    }

    private MatchActionEntity appendGiftTriggerAction(
        Long matchId,
        Long userId,
        int turnNumber,
        Map<String, Object> payload,
        int actionOrder
    ) {
        MatchActionEntity action = new MatchActionEntity();
        action.setMatchId(matchId);
        action.setUserId(userId);
        action.setActionType(ACTION_TYPE_GIFT_TRIGGER);
        action.setPayload(matchPayloadJsonService.toJson(payload));
        action.setTurnNumber(turnNumber);
        action.setActionOrder(actionOrder);
        action.setExecutedAt(LocalDateTime.now());
        return matchActionRepository.save(action);
    }
}
