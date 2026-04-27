package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class CollabGiftPreviewHandler implements CollabTriggerHandler {

    @Override
    public String handlerKey() {
        return "COLLAB_GIFT_PREVIEW";
    }

    @Override
    public boolean supports(CollabEventType eventType) {
        return eventType == CollabEventType.COLLAB_GIFT_PREVIEW_CREATED;
    }

    @Override
    public CollabTriggerHandlingResult handle(CollabEvent event) {
        return CollabTriggerHandlingResult.syncHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
