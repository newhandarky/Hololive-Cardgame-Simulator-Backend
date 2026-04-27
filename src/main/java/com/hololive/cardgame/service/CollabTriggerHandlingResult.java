package com.hololive.cardgame.service;

import java.util.List;

public record CollabTriggerHandlingResult(
    String handlerKey,
    CollabEventType eventType,
    CollabTriggerExecutionMode executionMode,
    boolean handled,
    List<String> warnings
) {

    public CollabTriggerHandlingResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static CollabTriggerHandlingResult syncHandled(String handlerKey, CollabEventType eventType) {
        return new CollabTriggerHandlingResult(handlerKey, eventType, CollabTriggerExecutionMode.SYNC, true, List.of());
    }

    public static CollabTriggerHandlingResult deferredHandled(String handlerKey, CollabEventType eventType) {
        return new CollabTriggerHandlingResult(handlerKey, eventType, CollabTriggerExecutionMode.DEFERRED, true, List.of());
    }
}
