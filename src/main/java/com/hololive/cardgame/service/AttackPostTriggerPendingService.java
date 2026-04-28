package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

public class AttackPostTriggerPendingService {

    private final PendingDecisionCreator pendingDecisionCreator;

    public AttackPostTriggerPendingService(PendingDecisionCreator pendingDecisionCreator) {
        this.pendingDecisionCreator = pendingDecisionCreator;
    }

    public AttackPostTriggerPendingResult resolvePending(AttackPostTriggerPendingContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack post trigger pending 缺少必要上下文");
        }

        Map<String, Object> postTriggerEffectSummary = buildAttackArtPostTriggerDeferredSummary(
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

    private Map<String, Object> buildAttackArtPostTriggerDeferredSummary(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> giftTriggers = safeList(giftTriggeredEffects);
        List<String> requestedEffects = new ArrayList<>();
        for (Map<String, Object> trigger : giftTriggers) {
            requestedEffects.addAll(toStringList(trigger == null ? null : trigger.get("requestedEffects")));
        }
        if (downEventPreview != null && !requestedEffects.contains("DOWN_EVENT")) {
            requestedEffects.add("DOWN_EVENT");
        }
        summary.put("sourceActionType", "ATTACK_ART_POST_TRIGGER");
        summary.put("deferred", !giftTriggers.isEmpty() || downEventPreview != null);
        summary.put("triggeredGifts", giftTriggers);
        summary.put("downEvent", downEventPreview);
        summary.put("requestedEffects", requestedEffects);
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        return summary;
    }

    private Map<String, Object> buildGiftTriggeredEffectDeferredSummary(List<Map<String, Object>> giftTriggeredEffects) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> triggers = safeList(giftTriggeredEffects);
        List<String> requestedEffects = new ArrayList<>();
        for (Map<String, Object> trigger : triggers) {
            for (String effectType : toStringList(trigger == null ? null : trigger.get("requestedEffects"))) {
                if (!requestedEffects.contains(effectType)) {
                    requestedEffects.add(effectType);
                }
            }
        }
        summary.put("sourceActionType", "GIFT");
        summary.put("deferred", !triggers.isEmpty());
        summary.put("triggeredGifts", triggers);
        summary.put("requestedEffects", requestedEffects);
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        return summary;
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> source) {
        return source == null || source.isEmpty() ? List.of() : source;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = normalizeZone(item);
            if (StringUtils.hasText(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private String normalizeZone(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? "" : text.toUpperCase();
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
