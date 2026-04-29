package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class GiftSelectionPendingContextBuilder {

    Map<String, Object> buildSelectionPendingContext(List<Map<String, Object>> giftTriggeredEffects) {
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> selectableTriggers = giftTriggeredEffects.stream()
            .filter(Objects::nonNull)
            .filter(trigger -> toBoolean(trigger.get("selectionRequired")))
            .toList();
        if (selectableTriggers.size() != 1) {
            return Map.of();
        }
        Map<String, Object> selectionTrigger = selectableTriggers.get(0);
        List<Long> candidateCardInstanceIds = toLongList(selectionTrigger.get("selectionCandidateCardInstanceIds"));
        if (candidateCardInstanceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("candidateCardInstanceIds", candidateCardInstanceIds);
        context.put("selectionGiftHolderCardInstanceId", asLong(selectionTrigger.get("giftHolderCardInstanceId")));
        context.put("minSelect", Math.max(asInt(selectionTrigger.get("selectionMinSelect")), 1));
        context.put(
            "maxSelect",
            Math.max(
                asInt(selectionTrigger.get("selectionMaxSelect")),
                Math.max(asInt(selectionTrigger.get("selectionMinSelect")), 1)
            )
        );
        return context;
    }

    private List<Long> toLongList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            Long id = asLong(item);
            if (id == null || id <= 0 || result.contains(id)) {
                continue;
            }
            result.add(id);
        }
        return result;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
