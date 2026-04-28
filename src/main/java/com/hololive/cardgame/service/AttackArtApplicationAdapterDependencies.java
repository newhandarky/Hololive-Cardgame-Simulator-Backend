package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import java.util.Map;

interface AttackArtApplicationAdapterDependencies {

    void appendGiftTriggerAction(MatchEntity match, Long userId, Map<String, Object> payload, int turnNumber);

    void touchUpdatedAt(MatchEntity match);

}
