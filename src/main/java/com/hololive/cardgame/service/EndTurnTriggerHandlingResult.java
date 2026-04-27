package com.hololive.cardgame.service;

import java.util.List;

public record EndTurnTriggerHandlingResult(
    String handlerKey,
    EndTurnEventType eventType,
    EndTurnTriggerExecutionMode executionMode,
    boolean handled,
    List<String> warnings
) {

    public EndTurnTriggerHandlingResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static EndTurnTriggerHandlingResult syncHandled(String handlerKey, EndTurnEventType eventType) {
        return new EndTurnTriggerHandlingResult(
            handlerKey,
            eventType,
            EndTurnTriggerExecutionMode.SYNC,
            true,
            List.of()
        );
    }

    public static EndTurnTriggerHandlingResult deferredHandled(String handlerKey, EndTurnEventType eventType) {
        return new EndTurnTriggerHandlingResult(
            handlerKey,
            eventType,
            EndTurnTriggerExecutionMode.DEFERRED,
            true,
            List.of()
        );
    }
}
