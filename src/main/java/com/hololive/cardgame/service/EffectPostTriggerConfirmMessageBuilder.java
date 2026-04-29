package com.hololive.cardgame.service;

import java.util.Locale;
import java.util.Map;

class EffectPostTriggerConfirmMessageBuilder {

    private static final String ACTION_TYPE_USE_OSHI_SKILL = "USE_OSHI_SKILL";

    String buildEffectPostTriggerConfirmMessage(
        String originSourceActionType,
        Map<String, Object> downEventPreview
    ) {
        String source = normalize(originSourceActionType);
        String sourceLabel = ACTION_TYPE_USE_OSHI_SKILL.equals(source) ? "Oshi 技能" : "卡片效果";
        int requestedLifeLoss = asInt(downEventPreview == null ? null : downEventPreview.get("requestedLifeLoss"));
        String downedCardId = asString(downEventPreview == null ? null : downEventPreview.get("downedCardId"));
        String rawText = asString(downEventPreview == null ? null : downEventPreview.get("rawText"));

        StringBuilder line = new StringBuilder("DOWN_EVENT");
        if (hasText(downedCardId)) {
            line.append(" (").append(downedCardId).append(")");
        }
        if (requestedLifeLoss > 0) {
            line.append("：額外失去生命 ").append(requestedLifeLoss);
        }
        if (hasText(rawText)) {
            line.append("\n").append(rawText);
        }
        return "是否要執行此 " + sourceLabel + " 的後續觸發效果？\n" + line;
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
