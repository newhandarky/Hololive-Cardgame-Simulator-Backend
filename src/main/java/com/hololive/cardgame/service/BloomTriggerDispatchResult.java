package com.hololive.cardgame.service;

import java.util.List;

public record BloomTriggerDispatchResult(
    List<BloomEvent> dispatchedEvents,
    List<String> invokedHandlerKeys,
    List<BloomTriggerHandlingResult> handlingResults
) {
}
