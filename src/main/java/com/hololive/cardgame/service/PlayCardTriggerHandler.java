package com.hololive.cardgame.service;

public interface PlayCardTriggerHandler {

    String handlerKey();

    boolean supports(PlayCardEventType eventType);

    PlayCardTriggerHandlingResult handle(PlayCardEvent event);
}
