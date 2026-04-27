package com.hololive.cardgame.service;

import java.util.List;

public record BloomTriggerHandlingResult(
    String handlerKey,
    BloomEventType eventType,
    BloomTriggerExecutionMode executionMode,
    boolean handled,
    List<String> warnings
) {

    public BloomTriggerHandlingResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static BloomTriggerHandlingResult syncHandled(String handlerKey, BloomEventType eventType) {
        return new BloomTriggerHandlingResult(handlerKey, eventType, BloomTriggerExecutionMode.SYNC, true, List.of());
    }

    public static BloomTriggerHandlingResult deferredHandled(String handlerKey, BloomEventType eventType) {
        return new BloomTriggerHandlingResult(handlerKey, eventType, BloomTriggerExecutionMode.DEFERRED, true, List.of());
    }
}
