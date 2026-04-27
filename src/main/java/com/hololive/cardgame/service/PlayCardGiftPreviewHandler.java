package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class PlayCardGiftPreviewHandler implements PlayCardTriggerHandler {

    @Override
    public String handlerKey() {
        return "PLAY_CARD_GIFT_PREVIEW";
    }

    @Override
    public boolean supports(PlayCardEventType eventType) {
        return eventType == PlayCardEventType.PLAY_CARD_GIFT_PREVIEW_CREATED;
    }

    @Override
    public PlayCardTriggerHandlingResult handle(PlayCardEvent event) {
        return PlayCardTriggerHandlingResult.syncHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
