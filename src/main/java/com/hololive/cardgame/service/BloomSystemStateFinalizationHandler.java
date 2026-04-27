package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class BloomSystemStateFinalizationHandler implements BloomTriggerHandler {

    @Override
    public String handlerKey() {
        return "BLOOM_SYSTEM_STATE_FINALIZATION";
    }

    @Override
    public boolean supports(BloomEventType eventType) {
        return eventType == BloomEventType.BLOOM_REQUEST_ACCEPTED || eventType == BloomEventType.BLOOM_RESOLVED;
    }

    @Override
    public BloomTriggerHandlingResult handle(BloomEvent event) {
        return BloomTriggerHandlingResult.syncHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
