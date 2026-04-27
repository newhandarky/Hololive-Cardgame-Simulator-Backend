package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PlayCardTriggerDispatcher {

    private final List<PlayCardTriggerHandler> handlers;

    @Autowired
    public PlayCardTriggerDispatcher(List<PlayCardTriggerHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public PlayCardTriggerDispatchResult dispatch(List<PlayCardEvent> events) {
        if (events == null || events.isEmpty()) {
            return new PlayCardTriggerDispatchResult(List.of(), List.of(), List.of());
        }

        List<String> invokedHandlerKeys = new ArrayList<>();
        List<PlayCardTriggerHandlingResult> handlingResults = new ArrayList<>();
        PlayCardEventType previousType = null;
        for (PlayCardEvent event : events) {
            if (event == null) {
                continue;
            }
            validateOrder(previousType, event.eventType());
            PlayCardTriggerHandler handler = resolveHandler(event.eventType());
            PlayCardTriggerHandlingResult handlingResult = handler.handle(event);
            if (handlingResult == null) {
                throw new IllegalStateException("PLAY_CARD trigger handler 不可回傳 null: " + handler.handlerKey());
            }
            invokedHandlerKeys.add(handlingResult.handlerKey());
            handlingResults.add(handlingResult);
            previousType = event.eventType();
        }
        return new PlayCardTriggerDispatchResult(
            List.copyOf(events),
            List.copyOf(invokedHandlerKeys),
            List.copyOf(handlingResults)
        );
    }

    private void validateOrder(PlayCardEventType previous, PlayCardEventType current) {
        if (previous == null || current == null) {
            return;
        }
        if (previous.ordinal() > current.ordinal()) {
            throw new IllegalStateException(
                "PLAY_CARD event 順序不合法: previous=" + previous + ", current=" + current
            );
        }
    }

    private PlayCardTriggerHandler resolveHandler(PlayCardEventType eventType) {
        return handlers.stream()
            .filter(handler -> handler.supports(eventType))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到對應的 PLAY_CARD trigger handler: " + eventType));
    }
}
