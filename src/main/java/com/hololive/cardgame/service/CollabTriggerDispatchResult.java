package com.hololive.cardgame.service;

import java.util.List;

public record CollabTriggerDispatchResult(
    List<CollabEvent> dispatchedEvents,
    List<String> invokedHandlerKeys,
    List<CollabTriggerHandlingResult> handlingResults
) {
}
