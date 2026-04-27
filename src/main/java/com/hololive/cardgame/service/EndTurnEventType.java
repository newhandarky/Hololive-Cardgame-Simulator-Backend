package com.hololive.cardgame.service;

public enum EndTurnEventType {
    TURN_ENDING,
    EXPIRED_TURN_EFFECTS_CLEARED,
    CENTER_REPLENISHED,
    TURN_ENDED,
    TURN_STARTED,
    PENDING_TURN_START_INTERACTION_CREATED
}
