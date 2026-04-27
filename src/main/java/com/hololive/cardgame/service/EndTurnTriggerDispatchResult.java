package com.hololive.cardgame.service;

import java.util.List;

public record EndTurnTriggerDispatchResult(
    List<EndTurnEvent> dispatchedEvents,
    List<String> invokedHandlerKeys
) {
}
