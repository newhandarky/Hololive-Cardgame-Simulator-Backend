package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AttachCheerTriggerDispatcher {

    private final List<AttachCheerTriggerHandler> handlers;

    @Autowired
    public AttachCheerTriggerDispatcher(List<AttachCheerTriggerHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public AttachCheerTriggerDispatchResult dispatch(List<AttachCheerEvent> events) {
        if (events == null || events.isEmpty()) {
            return new AttachCheerTriggerDispatchResult(List.of(), List.of(), List.of());
        }

        List<String> invokedHandlerKeys = new ArrayList<>();
        List<AttachCheerTriggerHandlingResult> handlingResults = new ArrayList<>();
        AttachCheerEventType previousType = null;
        for (AttachCheerEvent event : events) {
            if (event == null) {
                continue;
            }
            validateOrder(previousType, event.eventType());
            AttachCheerTriggerHandler handler = resolveHandler(event.eventType());
            AttachCheerTriggerHandlingResult handlingResult = handler.handle(event);
            if (handlingResult == null) {
                throw new IllegalStateException("ATTACH_CHEER trigger handler 不可回傳 null: " + handler.handlerKey());
            }
            invokedHandlerKeys.add(handlingResult.handlerKey());
            handlingResults.add(handlingResult);
            previousType = event.eventType();
        }
        return new AttachCheerTriggerDispatchResult(
            List.copyOf(events),
            List.copyOf(invokedHandlerKeys),
            List.copyOf(handlingResults)
        );
    }

    private void validateOrder(AttachCheerEventType previous, AttachCheerEventType current) {
        if (previous == null || current == null) {
            return;
        }
        if (previous.ordinal() > current.ordinal()) {
            throw new IllegalStateException(
                "ATTACH_CHEER event 順序不合法: previous=" + previous + ", current=" + current
            );
        }
    }

    private AttachCheerTriggerHandler resolveHandler(AttachCheerEventType eventType) {
        return handlers.stream()
            .filter(handler -> handler.supports(eventType))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到對應的 ATTACH_CHEER trigger handler: " + eventType));
    }
}
