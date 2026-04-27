package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class EndTurnSystemStateFinalizationHandler implements EndTurnTriggerHandler {

    @Override
    public String handlerKey() {
        return "SYSTEM_STATE_FINALIZATION";
    }

    @Override
    public boolean supports(EndTurnEventType eventType) {
        return switch (eventType) {
            case TURN_ENDING, EXPIRED_TURN_EFFECTS_CLEARED, CENTER_REPLENISHED, TURN_ENDED, TURN_STARTED -> true;
            case PENDING_TURN_START_INTERACTION_CREATED -> false;
        };
    }

    @Override
    public void handle(EndTurnEvent event) {
        // 第一版 pilot 只建立 deterministic handler flow，實際 state mutation 已在 resolver / bridge 完成。
    }
}
