package com.hololive.cardgame.service;

import java.util.List;

public record AttachCheerTriggerDispatchResult(
    List<AttachCheerEvent> events,
    List<String> invokedHandlerKeys,
    List<AttachCheerTriggerHandlingResult> handlingResults
) {

    public AttachCheerTriggerDispatchResult {
        events = events == null ? List.of() : List.copyOf(events);
        invokedHandlerKeys = invokedHandlerKeys == null ? List.of() : List.copyOf(invokedHandlerKeys);
        handlingResults = handlingResults == null ? List.of() : List.copyOf(handlingResults);
    }
}
