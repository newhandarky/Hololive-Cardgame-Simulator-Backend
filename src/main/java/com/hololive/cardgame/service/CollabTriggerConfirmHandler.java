package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class CollabTriggerConfirmHandler implements CollabTriggerHandler {

    @Override
    public String handlerKey() {
        return "COLLAB_TRIGGER_CONFIRM";
    }

    @Override
    public boolean supports(CollabEventType eventType) {
        return eventType == CollabEventType.COLLAB_TRIGGER_CONFIRM_REQUIRED;
    }

    @Override
    public CollabTriggerHandlingResult handle(CollabEvent event) {
        return CollabTriggerHandlingResult.deferredHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
