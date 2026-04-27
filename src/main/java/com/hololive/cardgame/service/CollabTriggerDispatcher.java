package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CollabTriggerDispatcher {

    private final List<CollabTriggerHandler> handlers;

    @Autowired
    public CollabTriggerDispatcher(List<CollabTriggerHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public CollabTriggerDispatchResult dispatch(List<CollabEvent> events) {
        if (events == null || events.isEmpty()) {
            return new CollabTriggerDispatchResult(List.of(), List.of(), List.of());
        }

        List<String> invokedHandlerKeys = new ArrayList<>();
        List<CollabTriggerHandlingResult> handlingResults = new ArrayList<>();
        CollabEventType previousType = null;
        for (CollabEvent event : events) {
            if (event == null) {
                continue;
            }
            validateOrder(previousType, event.eventType());
            CollabTriggerHandler handler = resolveHandler(event.eventType());
            CollabTriggerHandlingResult handlingResult = handler.handle(event);
            if (handlingResult == null) {
                throw new IllegalStateException("COLLAB trigger handler 不可回傳 null: " + handler.handlerKey());
            }
            invokedHandlerKeys.add(handlingResult.handlerKey());
            handlingResults.add(handlingResult);
            previousType = event.eventType();
        }
        return new CollabTriggerDispatchResult(
            List.copyOf(events),
            List.copyOf(invokedHandlerKeys),
            List.copyOf(handlingResults)
        );
    }

    private void validateOrder(CollabEventType previous, CollabEventType current) {
        if (previous == null || current == null) {
            return;
        }
        if (previous.ordinal() > current.ordinal()) {
            throw new IllegalStateException(
                "COLLAB event 順序不合法: previous=" + previous + ", current=" + current
            );
        }
    }

    private CollabTriggerHandler resolveHandler(CollabEventType eventType) {
        return handlers.stream()
            .filter(handler -> handler.supports(eventType))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到對應的 COLLAB trigger handler: " + eventType));
    }
}
