package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BloomTriggerDispatcher {

    private final List<BloomTriggerHandler> handlers;

    @Autowired
    public BloomTriggerDispatcher(List<BloomTriggerHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public BloomTriggerDispatchResult dispatch(List<BloomEvent> events) {
        if (events == null || events.isEmpty()) {
            return new BloomTriggerDispatchResult(List.of(), List.of(), List.of());
        }

        List<String> invokedHandlerKeys = new ArrayList<>();
        List<BloomTriggerHandlingResult> handlingResults = new ArrayList<>();
        BloomEventType previousType = null;
        for (BloomEvent event : events) {
            if (event == null) {
                continue;
            }
            validateOrder(previousType, event.eventType());
            BloomTriggerHandler handler = resolveHandler(event.eventType());
            BloomTriggerHandlingResult handlingResult = handler.handle(event);
            if (handlingResult == null) {
                throw new IllegalStateException("BLOOM trigger handler 不可回傳 null: " + handler.handlerKey());
            }
            invokedHandlerKeys.add(handlingResult.handlerKey());
            handlingResults.add(handlingResult);
            previousType = event.eventType();
        }
        return new BloomTriggerDispatchResult(
            List.copyOf(events),
            List.copyOf(invokedHandlerKeys),
            List.copyOf(handlingResults)
        );
    }

    private void validateOrder(BloomEventType previous, BloomEventType current) {
        if (previous == null || current == null) {
            return;
        }
        if (previous.ordinal() > current.ordinal()) {
            throw new IllegalStateException(
                "BLOOM event 順序不合法: previous=" + previous + ", current=" + current
            );
        }
    }

    private BloomTriggerHandler resolveHandler(BloomEventType eventType) {
        return handlers.stream()
            .filter(handler -> handler.supports(eventType))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到對應的 BLOOM trigger handler: " + eventType));
    }
}
