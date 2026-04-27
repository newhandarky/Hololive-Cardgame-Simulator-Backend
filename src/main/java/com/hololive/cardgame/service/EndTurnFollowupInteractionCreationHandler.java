package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class EndTurnFollowupInteractionCreationHandler implements EndTurnTriggerHandler {

    @Override
    public String handlerKey() {
        return "FOLLOWUP_INTERACTION_CREATION";
    }

    @Override
    public boolean supports(EndTurnEventType eventType) {
        return eventType == EndTurnEventType.PENDING_TURN_START_INTERACTION_CREATED;
    }

    @Override
    public void handle(EndTurnEvent event) {
        // 第一版 pilot 只建立 handler contract，實際 follow-up 邏輯仍由 legacy bridge 處理。
    }
}
