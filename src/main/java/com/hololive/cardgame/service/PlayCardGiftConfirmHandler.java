package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class PlayCardGiftConfirmHandler implements PlayCardTriggerHandler {

    @Override
    public String handlerKey() {
        return "PLAY_CARD_GIFT_CONFIRM";
    }

    @Override
    public boolean supports(PlayCardEventType eventType) {
        return eventType == PlayCardEventType.PLAY_CARD_GIFT_CONFIRM_REQUIRED;
    }

    @Override
    public PlayCardTriggerHandlingResult handle(PlayCardEvent event) {
        return PlayCardTriggerHandlingResult.deferredHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
