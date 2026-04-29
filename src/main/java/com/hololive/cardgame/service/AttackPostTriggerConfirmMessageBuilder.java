package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class AttackPostTriggerConfirmMessageBuilder {

    private final GiftTriggeredEffectDetailsMessageBuilder giftTriggeredEffectDetailsMessageBuilder;

    AttackPostTriggerConfirmMessageBuilder() {
        this.giftTriggeredEffectDetailsMessageBuilder = new GiftTriggeredEffectDetailsMessageBuilder();
    }

    String buildAttackArtPostTriggerConfirmMessage(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview
    ) {
        List<String> lines = new ArrayList<>();
        if (downEventPreview != null && !downEventPreview.isEmpty()) {
            int requestedLifeLoss = asInt(downEventPreview.get("requestedLifeLoss"));
            String downedCardId = asString(downEventPreview.get("downedCardId"));
            String rawText = asString(downEventPreview.get("rawText"));
            StringBuilder line = new StringBuilder("[Down Event]\n");
            line.append("DOWN_EVENT");
            if (hasText(downedCardId)) {
                line.append(" (").append(downedCardId).append(")");
            }
            if (requestedLifeLoss > 0) {
                line.append("：額外失去生命 ").append(requestedLifeLoss);
            }
            if (hasText(rawText)) {
                line.append("\n").append(rawText);
            }
            lines.add(line.toString());
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            String giftDetails = buildGiftTriggeredEffectDetails(giftTriggeredEffects);
            if (hasText(giftDetails)) {
                lines.add("[Gift]\n" + giftDetails);
            }
        }
        if (lines.isEmpty()) {
            return "是否要執行攻擊後觸發效果？";
        }
        return "是否要執行攻擊後觸發效果？\n" + String.join("\n\n", lines);
    }

    String buildGiftTriggeredEffectDetails(List<Map<String, Object>> giftTriggeredEffects) {
        return giftTriggeredEffectDetailsMessageBuilder.buildGiftTriggeredEffectDetails(giftTriggeredEffects);
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
