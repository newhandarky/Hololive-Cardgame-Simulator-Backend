package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

class AttackArtPostTriggerConfirmPendingInputBuilder {

    private static final String SOURCE_ACTION_TYPE = "ATTACK_ART_POST_TRIGGER";
    private static final String EFFECT_TYPE = "ATTACK_ART_POST_TRIGGER";
    private static final String TITLE = "確認攻擊後觸發效果";

    private final GiftTriggerPendingPayloadBuilder giftTriggerPendingPayloadBuilder;
    private final GiftSelectionPendingContextBuilder giftSelectionPendingContextBuilder;
    private final AttackPostTriggerSectionBuilder attackPostTriggerSectionBuilder;
    private final AttackPostTriggerConfirmMessageBuilder attackPostTriggerConfirmMessageBuilder;

    AttackArtPostTriggerConfirmPendingInputBuilder() {
        this.giftTriggerPendingPayloadBuilder = new GiftTriggerPendingPayloadBuilder();
        this.giftSelectionPendingContextBuilder = new GiftSelectionPendingContextBuilder();
        this.attackPostTriggerSectionBuilder = new AttackPostTriggerSectionBuilder();
        this.attackPostTriggerConfirmMessageBuilder = new AttackPostTriggerConfirmMessageBuilder();
    }

    FollowupTriggerConfirmPendingDecisionInput buildAttackArtPostTriggerConfirmPendingInput(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> cards,
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview,
        int turnNumber
    ) {
        List<Map<String, Object>> giftTriggers = giftTriggerPendingPayloadBuilder.buildGiftTriggerPayloads(giftTriggeredEffects);
        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("giftTriggers", giftTriggers);
        additionalContext.put("giftCount", giftTriggers.size());
        additionalContext.putAll(giftSelectionPendingContextBuilder.buildSelectionPendingContext(giftTriggeredEffects));
        if (downEventPreview != null && !downEventPreview.isEmpty()) {
            additionalContext.put("downEvent", buildDownEventContext(downEventPreview));
        }
        additionalContext.put(
            "triggerSections",
            attackPostTriggerSectionBuilder.buildAttackArtPostTriggerSections(giftTriggeredEffects, downEventPreview)
        );

        return new FollowupTriggerConfirmPendingDecisionInput(
            matchId,
            userId,
            SOURCE_ACTION_TYPE,
            sourceCardInstanceId,
            sourceCardId,
            EFFECT_TYPE,
            TITLE,
            attackPostTriggerConfirmMessageBuilder.buildAttackArtPostTriggerConfirmMessage(giftTriggeredEffects, downEventPreview),
            cards,
            turnNumber,
            additionalContext
        );
    }

    private Map<String, Object> buildDownEventContext(Map<String, Object> downEventPreview) {
        Map<String, Object> downEvent = new LinkedHashMap<>();
        downEvent.put("downedOwnerUserId", asLong(downEventPreview.get("downedOwnerUserId")));
        downEvent.put("downedCardId", asString(downEventPreview.get("downedCardId")));
        downEvent.put("downedStageZone", asString(downEventPreview.get("downedStageZone")));
        downEvent.put("turnNumber", asInt(downEventPreview.get("turnNumber")));
        downEvent.put("rawText", asString(downEventPreview.get("rawText")));
        downEvent.put("requestedLifeLoss", asInt(downEventPreview.get("requestedLifeLoss")));
        return downEvent;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
