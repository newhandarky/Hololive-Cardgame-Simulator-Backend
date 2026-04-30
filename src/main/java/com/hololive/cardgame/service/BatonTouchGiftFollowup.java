package com.hololive.cardgame.service;

import java.util.Map;

record BatonTouchGiftFollowup(
    Map<String, Object> giftEffectSummary,
    FollowupInteractionDecision decision
) {
    static BatonTouchGiftFollowup empty() {
        return new BatonTouchGiftFollowup(null, null);
    }

    boolean hasGiftEffects() {
        return giftEffectSummary != null;
    }
}
