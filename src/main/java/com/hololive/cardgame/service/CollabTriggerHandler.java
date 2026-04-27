package com.hololive.cardgame.service;

public interface CollabTriggerHandler {

    String handlerKey();

    boolean supports(CollabEventType eventType);

    CollabTriggerHandlingResult handle(CollabEvent event);
}
