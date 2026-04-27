package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class PlayCardSystemStateFinalizationHandler implements PlayCardTriggerHandler {

    @Override
    public String handlerKey() {
        return "PLAY_CARD_SYSTEM_STATE_FINALIZATION";
    }

    @Override
    public boolean supports(PlayCardEventType eventType) {
        return eventType == PlayCardEventType.PLAY_CARD_REQUEST_ACCEPTED ||
            eventType == PlayCardEventType.PLAY_CARD_RESOLVED;
    }

    @Override
    public PlayCardTriggerHandlingResult handle(PlayCardEvent event) {
        return PlayCardTriggerHandlingResult.syncHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
