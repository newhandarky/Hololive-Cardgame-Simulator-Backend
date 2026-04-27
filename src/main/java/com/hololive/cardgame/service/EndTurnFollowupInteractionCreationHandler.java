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
    public EndTurnTriggerHandlingResult handle(EndTurnEvent event) {
        // 目前 END_TURN 的 follow-up interaction 已由 application / legacy bridge 建立，
        // trigger handler 在這裡只負責表達這條 follow-up 屬於 deferred 類型。
        return EndTurnTriggerHandlingResult.deferredHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
