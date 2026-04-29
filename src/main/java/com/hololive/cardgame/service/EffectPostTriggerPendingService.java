package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class EffectPostTriggerPendingService {

    private static final String ACTION_TYPE_USE_OSHI_SKILL = "USE_OSHI_SKILL";
    private static final String ACTION_TYPE_EFFECT_POST_TRIGGER = "EFFECT_POST_TRIGGER";

    private final FollowupCardCandidateLoader followupCardCandidateLoader;
    private final DownEventPreviewExtractor downEventPreviewExtractor;
    private final EffectPostTriggerConfirmMessageBuilder effectPostTriggerConfirmMessageBuilder;
    private final FollowupTriggerConfirmPendingDecisionCreator followupTriggerConfirmPendingDecisionCreator;

    EffectPostTriggerPendingService(
        JdbcTemplate jdbcTemplate,
        EffectPostTriggerConfirmMessageBuilder effectPostTriggerConfirmMessageBuilder,
        FollowupTriggerConfirmPendingDecisionCreator followupTriggerConfirmPendingDecisionCreator
    ) {
        this.followupCardCandidateLoader = new FollowupCardCandidateLoader(jdbcTemplate);
        this.downEventPreviewExtractor = new DownEventPreviewExtractor();
        this.effectPostTriggerConfirmMessageBuilder = effectPostTriggerConfirmMessageBuilder;
        this.followupTriggerConfirmPendingDecisionCreator = followupTriggerConfirmPendingDecisionCreator;
    }

    FollowupInteractionDecision createEffectPostTriggerConfirmPendingInteractionIfNeeded(
        Long matchId,
        Long userId,
        String originSourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        Map<String, Object> effectSummary,
        int turnNumber
    ) {
        Map<String, Object> downEventPreview = downEventPreviewExtractor.extractDownEventPreview(effectSummary);
        if (downEventPreview == null || downEventPreview.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        if (sourceCardInstanceId != null && sourceCardInstanceId > 0) {
            String fallbackZone = ACTION_TYPE_USE_OSHI_SKILL.equals(normalize(originSourceActionType))
                ? "OSHI"
                : "ARCHIVE";
            cards.add(buildSourceCardPayload(matchId, userId, sourceCardInstanceId, sourceCardId, fallbackZone));
        }

        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("downEvent", buildDownEventContext(downEventPreview));
        additionalContext.put("originSourceActionType", normalize(originSourceActionType));

        return followupTriggerConfirmPendingDecisionCreator.create(
            matchId,
            userId,
            ACTION_TYPE_EFFECT_POST_TRIGGER,
            sourceCardInstanceId,
            sourceCardId,
            "DOWN_EVENT",
            "確認觸發效果",
            effectPostTriggerConfirmMessageBuilder.buildEffectPostTriggerConfirmMessage(
                originSourceActionType,
                downEventPreview
            ),
            cards,
            turnNumber,
            additionalContext
        );
    }

    private Map<String, Object> buildSourceCardPayload(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String fallbackCardId,
        String fallbackZone
    ) {
        Map<String, Object> card = followupCardCandidateLoader.loadCardCandidateForDecision(
            matchId,
            userId,
            userId,
            cardInstanceId,
            fallbackZone,
            fallbackCardId
        );
        if (!card.containsKey("cardInstanceId")) {
            card.put("cardInstanceId", cardInstanceId);
        }
        if (!card.containsKey("cardId")) {
            card.put("cardId", fallbackCardId);
        }
        return card;
    }

    private Map<String, Object> buildDownEventContext(Map<String, Object> downEventPreview) {
        Map<String, Object> downEventContext = new LinkedHashMap<>();
        downEventContext.put("downedOwnerUserId", asLong(downEventPreview.get("downedOwnerUserId")));
        downEventContext.put("downedCardId", asString(downEventPreview.get("downedCardId")));
        downEventContext.put("downedStageZone", asString(downEventPreview.get("downedStageZone")));
        downEventContext.put("turnNumber", asInt(downEventPreview.get("turnNumber")));
        downEventContext.put("rawText", asString(downEventPreview.get("rawText")));
        downEventContext.put("requestedLifeLoss", asInt(downEventPreview.get("requestedLifeLoss")));
        return downEventContext;
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
}
