package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class CollabSystemStateFinalizationHandler implements CollabTriggerHandler {

    @Override
    public String handlerKey() {
        return "COLLAB_SYSTEM_STATE_FINALIZATION";
    }

    @Override
    public boolean supports(CollabEventType eventType) {
        return eventType == CollabEventType.COLLAB_REQUEST_ACCEPTED || eventType == CollabEventType.COLLAB_RESOLVED;
    }

    @Override
    public CollabTriggerHandlingResult handle(CollabEvent event) {
        return CollabTriggerHandlingResult.syncHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
