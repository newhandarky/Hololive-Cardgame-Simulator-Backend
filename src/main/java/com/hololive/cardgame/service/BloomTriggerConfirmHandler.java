package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class BloomTriggerConfirmHandler implements BloomTriggerHandler {

    @Override
    public String handlerKey() {
        return "BLOOM_TRIGGER_CONFIRM";
    }

    @Override
    public boolean supports(BloomEventType eventType) {
        return eventType == BloomEventType.BLOOM_TRIGGER_CONFIRM_REQUIRED;
    }

    @Override
    public BloomTriggerHandlingResult handle(BloomEvent event) {
        return BloomTriggerHandlingResult.deferredHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
