package com.hololive.cardgame.service;

public interface AttachCheerTriggerHandler {

    String handlerKey();

    boolean supports(AttachCheerEventType eventType);

    AttachCheerTriggerHandlingResult handle(AttachCheerEvent event);
}
