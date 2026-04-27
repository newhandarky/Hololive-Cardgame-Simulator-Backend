package com.hololive.cardgame.service;

import java.util.List;

public record AttachCheerTriggerHandlingResult(
    String handlerKey,
    AttachCheerEventType eventType,
    AttachCheerTriggerExecutionMode executionMode,
    boolean handled,
    List<String> warnings
) {

    public AttachCheerTriggerHandlingResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static AttachCheerTriggerHandlingResult syncHandled(String handlerKey, AttachCheerEventType eventType) {
        return new AttachCheerTriggerHandlingResult(
            handlerKey,
            eventType,
            AttachCheerTriggerExecutionMode.SYNC,
            true,
            List.of()
        );
    }
}
