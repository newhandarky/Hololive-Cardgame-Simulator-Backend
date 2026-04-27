package com.hololive.cardgame.service;

public interface EndTurnTriggerHandler {

    String handlerKey();

    boolean supports(EndTurnEventType eventType);

    void handle(EndTurnEvent event);
}
