package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class AttackPostTriggerConfirmMessageBuilder {

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
            lines.add("[Gift]\n" + buildGiftTriggeredEffectDetails(giftTriggeredEffects));
        }
        if (lines.isEmpty()) {
            return "是否要執行攻擊後觸發效果？";
        }
        return "是否要執行攻擊後觸發效果？\n" + String.join("\n\n", lines);
    }

    String buildGiftTriggeredEffectDetails(List<Map<String, Object>> giftTriggeredEffects) {
        int count = 0;
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> trigger : giftTriggeredEffects) {
            count++;
            String cardId = asString(trigger.get("giftHolderCardId"));
            String triggerType = normalize(trigger.get("triggerType"));
            String rawText = asString(trigger.get("rawText"));
            List<String> effectTypes = toStringList(trigger.get("requestedEffects"));
            String effectSummary = effectTypes.isEmpty() ? "無可解析效果類型" : String.join("、", effectTypes);
            StringBuilder line = new StringBuilder();
            line.append("#").append(count).append(" ");
            if (hasText(cardId)) {
                line.append(cardId).append(" ");
            }
            line.append("[").append(hasText(triggerType) ? triggerType : "GIFT").append("]");
            line.append(" 效果類型：").append(effectSummary);
            if (hasText(rawText)) {
                line.append("\n").append(rawText);
            }
            lines.add(line.toString());
        }
        return String.join("\n\n", lines);
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = normalize(item);
            if (!hasText(text) || result.contains(text)) {
                continue;
            }
            result.add(text);
        }
        return result;
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
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
