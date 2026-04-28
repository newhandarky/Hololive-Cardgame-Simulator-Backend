package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import java.util.List;
import java.util.Map;

interface AttackArtApplicationAdapterDependencies {

    void appendGiftTriggerAction(MatchEntity match, Long userId, Map<String, Object> payload, int turnNumber);

    boolean hasAvailableArtAttacker(Long matchId, Long userId, int turnNumber);

    void touchUpdatedAt(MatchEntity match);

    List<Map<String, Object>> loadSelfDownedFanSupportSnapshots(Long matchId, Long ownerUserId, Long holderHolomemId);

    List<Map<String, Object>> extractExecutedEffectSummaries(Map<String, Object> effectSummary);

}
