package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class PlayCardEnterHookHandler implements PlayCardTriggerHandler {

    @Override
    public String handlerKey() {
        return "PLAY_CARD_ENTER_HOOK";
    }

    @Override
    public boolean supports(PlayCardEventType eventType) {
        return eventType == PlayCardEventType.PLAY_CARD_ENTER_HOOK_RESOLVED;
    }

    @Override
    public PlayCardTriggerHandlingResult handle(PlayCardEvent event) {
        return PlayCardTriggerHandlingResult.syncHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
