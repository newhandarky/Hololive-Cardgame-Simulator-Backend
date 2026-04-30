package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

record AttackApplicationPreDamageStage(
    AttackEffectFollowupResult result,
    HoloxSlotRevealSummary holoxSlotRevealSummary
) {
}

record AttackApplicationCostStage(
    Map<String, Integer> baseRequiredCheerCost,
    Map<String, Integer> passiveGiftArtCostReduction,
    Map<String, Integer> requiredCheerCost,
    Map<String, Object> costSummary
) {
}

record AttackApplicationTargetStage(
    AttackTargetResult result,
    Map<String, Object> defenderSelfDownedHolderSnapshot,
    List<Map<String, Object>> defenderSelfDownedFanSupportSnapshots
) {
}

record AttackApplicationDamageStage(
    AttackDamageResult result,
    int totalDamage
) {
}

record AttackApplicationDamagePreventionStage(
    AttackEffectDamagePreventionResult result,
    Map<String, Object> defenderDamageReceivedGiftSummary,
    int adjustedDamage
) {
}

record AttackApplicationDamageApplicationStage(
    AttackDamageApplicationResult result,
    Map<String, Object> artSummary,
    Long lostLifeCardInstanceId
) {
}

record AttackApplicationPostDamageStage(
    AttackEffectPostDamageResult result
) {
}

record AttackApplicationDownStage(
    AttackDownResult result,
    String downedTargetCardId,
    String downedTargetZone
) {
}

record AttackApplicationDefenderGiftStage(
    AttackDefenderGiftFollowupResult result
) {
}

record AttackApplicationPendingStage(
    AttackPostTriggerPendingResult result,
    FollowupInteractionDecision postTriggerConfirmDecision,
    FollowupInteractionDecision defenderGiftConfirmDecision
) {
}

record AttackApplicationRestPayloadStage(
    AttackRestAndPayloadResult result
) implements AttackArtApplicationService.AttackPayloadCarrier {

    @Override
    public Map<String, Object> payload() {
        return result.payload();
    }
}
