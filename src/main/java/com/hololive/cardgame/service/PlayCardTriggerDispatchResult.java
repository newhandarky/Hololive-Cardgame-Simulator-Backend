package com.hololive.cardgame.service;

import java.util.List;

public record PlayCardTriggerDispatchResult(
    List<PlayCardEvent> events,
    List<String> invokedHandlerKeys,
    List<PlayCardTriggerHandlingResult> handlingResults
) {

    public PlayCardTriggerDispatchResult {
        events = events == null ? List.of() : List.copyOf(events);
        invokedHandlerKeys = invokedHandlerKeys == null ? List.of() : List.copyOf(invokedHandlerKeys);
        handlingResults = handlingResults == null ? List.of() : List.copyOf(handlingResults);
    }
}
