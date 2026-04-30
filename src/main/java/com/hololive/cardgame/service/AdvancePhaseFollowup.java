package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

record AdvancePhaseFollowup(
    List<Map<String, Object>> ownGiftEffects,
    List<Map<String, Object>> opponentGiftEffects,
    FollowupInteractionDecision ownDecision,
    FollowupInteractionDecision opponentDecision
) {
    static AdvancePhaseFollowup empty() {
        return new AdvancePhaseFollowup(List.of(), List.of(), null, null);
    }
}
