package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

public class AttackPostTriggerPendingService {

    private final PendingDecisionCreator pendingDecisionCreator;
    private final AttackArtPostTriggerDeferredSummaryBuilder attackArtPostTriggerDeferredSummaryBuilder;
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder;

    public AttackPostTriggerPendingService(PendingDecisionCreator pendingDecisionCreator) {
        this.pendingDecisionCreator = pendingDecisionCreator;
        this.attackArtPostTriggerDeferredSummaryBuilder = new AttackArtPostTriggerDeferredSummaryBuilder();
        this.giftTriggeredEffectDeferredSummaryBuilder = new GiftTriggeredEffectDeferredSummaryBuilder();
    }

    public AttackPostTriggerPendingResult resolvePending(AttackPostTriggerPendingContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack post trigger pending 缺少必要上下文");
        }

        Map<String, Object> postTriggerEffectSummary = attackArtPostTriggerDeferredSummaryBuilder.build(
            context.giftTriggeredEffects(),
            context.downEventPreview()
        );
        AttackPendingDecision postTriggerDecision = null;
        if (hasPostTriggerPending(context)) {
            postTriggerDecision = pendingDecisionCreator.createAttackPostTriggerPending(
                context,
                postTriggerEffectSummary
            );
        }

        Map<String, Object> defenderGiftEffectSummary = buildGiftTriggeredEffectDeferredSummary(
            context.defenderGiftTriggeredEffects()
        );
        AttackPendingDecision defenderGiftDecision = null;
        if (hasDefenderGiftPending(context)) {
            defenderGiftDecision = pendingDecisionCreator.createDefenderGiftPending(
                context,
                defenderGiftEffectSummary
            );
        }

        return new AttackPostTriggerPendingResult(
            postTriggerEffectSummary,
            postTriggerDecision,
            defenderGiftEffectSummary,
            defenderGiftDecision
        );
    }

    private boolean hasPostTriggerPending(AttackPostTriggerPendingContext context) {
        return !safeList(context.giftTriggeredEffects()).isEmpty()
            || (context.downEventPreview() != null && !context.downEventPreview().isEmpty());
    }

    private boolean hasDefenderGiftPending(AttackPostTriggerPendingContext context) {
        return !safeList(context.defenderGiftTriggeredEffects()).isEmpty();
    }

    private Map<String, Object> buildGiftTriggeredEffectDeferredSummary(List<Map<String, Object>> giftTriggeredEffects) {
        return giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(safeList(giftTriggeredEffects));
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> source) {
        return source == null || source.isEmpty() ? List.of() : source;
    }

    public interface PendingDecisionCreator {
        AttackPendingDecision createAttackPostTriggerPending(
            AttackPostTriggerPendingContext context,
            Map<String, Object> postTriggerEffectSummary
        );

        AttackPendingDecision createDefenderGiftPending(
            AttackPostTriggerPendingContext context,
            Map<String, Object> defenderGiftEffectSummary
        );
    }
}
