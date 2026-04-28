package com.hololive.cardgame.service;

import java.util.Map;

interface AttackArtApplicationAdapterDependencies {

    void appendGiftTriggerAction(Long matchId, Long userId, Map<String, Object> payload, int turnNumber);

}
