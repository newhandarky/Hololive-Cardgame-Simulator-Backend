package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EndTurnTriggerDispatcher {

    private final List<EndTurnTriggerHandler> handlers;

    @Autowired
    public EndTurnTriggerDispatcher(List<EndTurnTriggerHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public EndTurnTriggerDispatchResult dispatch(List<EndTurnEvent> events) {
        if (events == null || events.isEmpty()) {
            return new EndTurnTriggerDispatchResult(List.of(), List.of(), List.of());
        }

        List<String> invokedHandlerKeys = new ArrayList<>();
        List<EndTurnTriggerHandlingResult> handlingResults = new ArrayList<>();
        EndTurnEventType previousType = null;
        for (EndTurnEvent event : events) {
            if (event == null) {
                continue;
            }
            validateOrder(previousType, event.eventType());
            EndTurnTriggerHandler handler = resolveHandler(event.eventType());
            EndTurnTriggerHandlingResult handlingResult = handler.handle(event);
            if (handlingResult == null) {
                throw new IllegalStateException("END_TURN trigger handler 不可回傳 null: " + handler.handlerKey());
            }
            invokedHandlerKeys.add(handlingResult.handlerKey());
            handlingResults.add(handlingResult);
            previousType = event.eventType();
        }
        return new EndTurnTriggerDispatchResult(
            List.copyOf(events),
            List.copyOf(invokedHandlerKeys),
            List.copyOf(handlingResults)
        );
    }

    private void validateOrder(EndTurnEventType previous, EndTurnEventType current) {
        if (previous == null || current == null) {
            return;
        }
        if (previous.ordinal() > current.ordinal()) {
            throw new IllegalStateException(
                "END_TURN event 順序不合法: previous=" + previous + ", current=" + current
            );
        }
    }

    private EndTurnTriggerHandler resolveHandler(EndTurnEventType eventType) {
        return handlers.stream()
            .filter(handler -> handler.supports(eventType))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到對應的 END_TURN trigger handler: " + eventType));
    }
}
