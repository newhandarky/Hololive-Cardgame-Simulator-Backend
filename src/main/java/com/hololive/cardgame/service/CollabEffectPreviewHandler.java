package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class CollabEffectPreviewHandler implements CollabTriggerHandler {

    @Override
    public String handlerKey() {
        return "COLLAB_EFFECT_PREVIEW";
    }

    @Override
    public boolean supports(CollabEventType eventType) {
        return eventType == CollabEventType.COLLAB_EFFECT_PREVIEW_CREATED;
    }

    @Override
    public CollabTriggerHandlingResult handle(CollabEvent event) {
        return CollabTriggerHandlingResult.syncHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
