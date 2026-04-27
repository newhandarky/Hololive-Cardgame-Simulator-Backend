package com.hololive.cardgame.service;

import org.springframework.stereotype.Component;

@Component
public class AttachCheerSystemStateFinalizationHandler implements AttachCheerTriggerHandler {

    @Override
    public String handlerKey() {
        return "ATTACH_CHEER_SYSTEM_STATE_FINALIZATION";
    }

    @Override
    public boolean supports(AttachCheerEventType eventType) {
        return eventType == AttachCheerEventType.ATTACH_CHEER_REQUEST_ACCEPTED ||
            eventType == AttachCheerEventType.ATTACH_CHEER_RESOLVED;
    }

    @Override
    public AttachCheerTriggerHandlingResult handle(AttachCheerEvent event) {
        return AttachCheerTriggerHandlingResult.syncHandled(handlerKey(), event.eventType());
    }
}
