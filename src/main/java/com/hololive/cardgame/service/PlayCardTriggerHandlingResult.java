package com.hololive.cardgame.service;

import java.util.List;

public record PlayCardTriggerHandlingResult(
    String handlerKey,
    PlayCardEventType eventType,
    PlayCardTriggerExecutionMode executionMode,
    boolean handled,
    List<String> warnings
) {

    public PlayCardTriggerHandlingResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static PlayCardTriggerHandlingResult syncHandled(String handlerKey, PlayCardEventType eventType) {
        return new PlayCardTriggerHandlingResult(handlerKey, eventType, PlayCardTriggerExecutionMode.SYNC, true, List.of());
    }

    public static PlayCardTriggerHandlingResult deferredHandled(String handlerKey, PlayCardEventType eventType) {
        return new PlayCardTriggerHandlingResult(handlerKey, eventType, PlayCardTriggerExecutionMode.DEFERRED, true, List.of());
    }
}
