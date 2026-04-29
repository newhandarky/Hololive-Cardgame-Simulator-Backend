package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class FollowupInteractionContextBuilder {

    private static final String DECISION_TYPE_LOOK_TOP_DECK = "LOOK_TOP_DECK";
    private static final String DECISION_TYPE_LOOK_OPPONENT_HAND = "LOOK_OPPONENT_HAND";
    private static final String DECISION_TYPE_LOOK_HOLOPOWER = "LOOK_HOLOPOWER";
    private static final String DECISION_TYPE_REORDER_DECK_BOTTOM = "REORDER_DECK_BOTTOM";

    FollowupInteractionContext buildFollowupInteractionContext(
        Long userId,
        Map<String, Object> effectSummary,
        CardCandidateLoader cardCandidateLoader
    ) {
        if (effectSummary == null || effectSummary.isEmpty() || cardCandidateLoader == null) {
            return null;
        }
        Object executedEffects = effectSummary.get("executedEffects");
        if (!(executedEffects instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> effectRow)) {
                continue;
            }
            String resolvedType = normalize(effectRow.get("effectType"));
            if (!toBoolean(effectRow.get("applied"))) {
                continue;
            }
            if (DECISION_TYPE_LOOK_TOP_DECK.equals(resolvedType)) {
                FollowupInteractionContext context = buildLookTopDeckContext(userId, effectRow, cardCandidateLoader);
                if (context != null) {
                    return context;
                }
            }
            if (DECISION_TYPE_LOOK_OPPONENT_HAND.equals(resolvedType) || DECISION_TYPE_LOOK_HOLOPOWER.equals(resolvedType)) {
                return buildLookZoneContext(userId, effectRow, resolvedType, cardCandidateLoader);
            }
            if (DECISION_TYPE_REORDER_DECK_BOTTOM.equals(resolvedType) || "SEARCH".equals(resolvedType)) {
                FollowupInteractionContext context = buildReorderDeckBottomContext(userId, effectRow, cardCandidateLoader);
                if (context != null) {
                    return context;
                }
            }
        }
        return null;
    }

    private FollowupInteractionContext buildLookTopDeckContext(
        Long userId,
        Map<?, ?> effectRow,
        CardCandidateLoader cardCandidateLoader
    ) {
        Long lookedCardInstanceId = asLong(effectRow.get("lookedCardInstanceId"));
        String lookedCardId = asString(effectRow.get("lookedCardId"));
        if (lookedCardInstanceId == null || !hasText(lookedCardId)) {
            return null;
        }
        Map<String, Object> candidate = cardCandidateLoader.load(
            userId,
            userId,
            lookedCardInstanceId,
            "DECK",
            lookedCardId
        );
        return new FollowupInteractionContext(
            DECISION_TYPE_LOOK_TOP_DECK,
            "查看牌庫頂",
            "選擇保留在牌庫頂的卡片；若不選擇則放到底部。",
            0,
            1,
            List.of(candidate),
            List.of(lookedCardInstanceId),
            List.of("TOP", "BOTTOM"),
            lookedCardInstanceId,
            lookedCardId
        );
    }

    private FollowupInteractionContext buildLookZoneContext(
        Long userId,
        Map<?, ?> effectRow,
        String resolvedType,
        CardCandidateLoader cardCandidateLoader
    ) {
        Long lookedUserId = asLong(effectRow.get("lookedUserId"));
        String lookedZone = DECISION_TYPE_LOOK_OPPONENT_HAND.equals(resolvedType) ? "HAND" : "HOLOPOWER";
        List<Map<String, Object>> cards = buildLookZoneCandidateCards(
            userId,
            lookedUserId == null ? userId : lookedUserId,
            effectRow.get("lookedCards"),
            lookedZone,
            cardCandidateLoader
        );
        List<Long> candidateCardInstanceIds = cards.stream()
            .map(card -> asLong(card.get("cardInstanceId")))
            .filter(id -> id != null && id > 0)
            .toList();
        String title = DECISION_TYPE_LOOK_OPPONENT_HAND.equals(resolvedType) ? "查看對手手牌" : "查看 Holopower";
        String message = DECISION_TYPE_LOOK_OPPONENT_HAND.equals(resolvedType)
            ? "以下為本次效果可查看的對手手牌。"
            : "以下為本次效果可查看的 Holopower。";
        return new FollowupInteractionContext(
            resolvedType,
            title,
            message,
            0,
            0,
            cards,
            candidateCardInstanceIds,
            List.of(),
            null,
            null
        );
    }

    private FollowupInteractionContext buildReorderDeckBottomContext(
        Long userId,
        Map<?, ?> effectRow,
        CardCandidateLoader cardCandidateLoader
    ) {
        if (!toBoolean(effectRow.get("requiresDeckBottomReorder"))) {
            return null;
        }
        List<Map<String, Object>> cards = buildLookZoneCandidateCards(
            userId,
            userId,
            effectRow.get("deckBottomReorderCandidates"),
            "DECK",
            cardCandidateLoader
        );
        List<Long> candidateCardInstanceIds = cards.stream()
            .map(card -> asLong(card.get("cardInstanceId")))
            .filter(id -> id != null && id > 0)
            .toList();
        if (candidateCardInstanceIds.size() <= 1) {
            return null;
        }
        return new FollowupInteractionContext(
            DECISION_TYPE_REORDER_DECK_BOTTOM,
            "排序牌庫底",
            "請依你要的順序確認，將剩餘卡片放到牌庫底。",
            candidateCardInstanceIds.size(),
            candidateCardInstanceIds.size(),
            cards,
            candidateCardInstanceIds,
            List.of(),
            null,
            null
        );
    }

    private List<Map<String, Object>> buildLookZoneCandidateCards(
        Long viewerUserId,
        Long ownerUserId,
        Object lookedCardsObject,
        String fallbackZone,
        CardCandidateLoader cardCandidateLoader
    ) {
        if (!(lookedCardsObject instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawCard)) {
                continue;
            }
            Long cardInstanceId = asLong(rawCard.get("cardInstanceId"));
            String cardId = asString(rawCard.get("cardId"));
            if (cardInstanceId == null || !hasText(cardId)) {
                continue;
            }
            Map<String, Object> card = cardCandidateLoader.load(
                viewerUserId,
                ownerUserId,
                cardInstanceId,
                fallbackZone,
                cardId
            );
            cards.add(card);
        }
        return cards;
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
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

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(value.toString()) || "1".equals(value.toString());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @FunctionalInterface
    interface CardCandidateLoader {
        Map<String, Object> load(
            Long viewerUserId,
            Long ownerUserId,
            Long cardInstanceId,
            String fallbackZone,
            String fallbackCardId
        );
    }
}
