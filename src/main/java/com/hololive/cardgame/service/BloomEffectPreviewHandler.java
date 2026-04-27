package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class BloomEffectPreviewHandler implements BloomTriggerHandler {

    @Override
    public String handlerKey() {
        return "BLOOM_EFFECT_PREVIEW";
    }

    @Override
    public boolean supports(BloomEventType eventType) {
        return eventType == BloomEventType.BLOOM_EFFECT_PREVIEW_CREATED;
    }

    @Override
    public BloomTriggerHandlingResult handle(BloomEvent event) {
        return BloomTriggerHandlingResult.syncHandled(handlerKey(), event == null ? null : event.eventType());
    }
}
