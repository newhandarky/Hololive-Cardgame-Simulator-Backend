package com.hololive.cardgame.service;

public interface BloomTriggerHandler {

    String handlerKey();

    boolean supports(BloomEventType eventType);

    BloomTriggerHandlingResult handle(BloomEvent event);
}
