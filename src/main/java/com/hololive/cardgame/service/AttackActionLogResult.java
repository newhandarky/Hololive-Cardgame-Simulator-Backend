package com.hololive.cardgame.service;

public record AttackActionLogResult(
    Long actionId,
    Integer actionOrder,
    String actionType,
    String payloadJson
) {
}
