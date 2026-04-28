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
        summary.put("triggerSections", buildAttackArtPostTriggerSections(giftTriggers, downEventPreview));
        summary.put("requestedEffects", requestedEffects);
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        return summary;
    }

    private List<Map<String, Object>> buildAttackArtPostTriggerSections(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview
    ) {
        List<Map<String, Object>> sections = new ArrayList<>();
        if (downEventPreview != null && !downEventPreview.isEmpty()) {
            Map<String, Object> downSection = new LinkedHashMap<>();
            downSection.put("sectionType", "DOWN_EVENT");
            downSection.put("title", "Down Event");
            downSection.put("requestedLifeLoss", asInt(downEventPreview.get("requestedLifeLoss")));
            downSection.put("downedCardId", asString(downEventPreview.get("downedCardId")));
            downSection.put("rawText", asString(downEventPreview.get("rawText")));
            sections.add(downSection);
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            List<Map<String, Object>> giftItems = new ArrayList<>();
            for (Map<String, Object> trigger : giftTriggeredEffects) {
                if (trigger == null || trigger.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("triggerType", normalizeZone(trigger.get("triggerType")));
                item.put("giftHolderCardId", asString(trigger.get("giftHolderCardId")));
                item.put("rawText", asString(trigger.get("rawText")));
                item.put("requestedEffects", toStringList(trigger.get("requestedEffects")));
                giftItems.add(item);
            }
            if (!giftItems.isEmpty()) {
                Map<String, Object> giftSection = new LinkedHashMap<>();
                giftSection.put("sectionType", "GIFT");
                giftSection.put("title", "Gift");
                giftSection.put("count", giftItems.size());
                giftSection.put("items", giftItems);
                sections.add(giftSection);
            }
        }
        return sections;
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
            if (StringUtils.hasText(text) && !result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
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
