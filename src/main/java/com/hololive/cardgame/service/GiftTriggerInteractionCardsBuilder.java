package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

class GiftTriggerInteractionCardsBuilder {

    private final FollowupSourceCardPayloadBuilder followupSourceCardPayloadBuilder;

    GiftTriggerInteractionCardsBuilder(JdbcTemplate jdbcTemplate) {
        this.followupSourceCardPayloadBuilder = new FollowupSourceCardPayloadBuilder(jdbcTemplate);
    }

    List<Map<String, Object>> buildGiftTriggerInteractionCards(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        List<Map<String, Object>> cards = new ArrayList<>();
        if (sourceCardInstanceId != null && sourceCardInstanceId > 0) {
            cards.add(followupSourceCardPayloadBuilder.buildOwnedStageCard(matchId, userId, sourceCardInstanceId, sourceCardId));
        }
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return cards;
        }
        for (Map<String, Object> trigger : giftTriggeredEffects) {
            Long holderCardInstanceId = asLong(trigger.get("giftHolderCardInstanceId"));
            String holderCardId = asString(trigger.get("giftHolderCardId"));
            String holderZone = asString(trigger.get("giftHolderZone"));
            if (holderCardInstanceId == null || holderCardInstanceId <= 0) {
                continue;
            }
            boolean exists = cards.stream()
                .anyMatch(card -> holderCardInstanceId.equals(asLong(card.get("cardInstanceId"))));
            if (exists) {
                continue;
            }
            cards.add(
                followupSourceCardPayloadBuilder.buildOwnedCard(
                    matchId,
                    userId,
                    holderCardInstanceId,
                    StringUtils.hasText(holderZone) ? holderZone : "STAGE",
                    holderCardId
                )
            );
        }
        return cards;
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

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
