package com.hololive.cardgame.service;

import static com.hololive.cardgame.service.MatchEffectValueHelper.asInt;
import static com.hololive.cardgame.service.MatchEffectValueHelper.asLong;
import static com.hololive.cardgame.service.MatchEffectValueHelper.asText;
import static com.hololive.cardgame.service.MatchEffectValueHelper.normalize;
import static com.hololive.cardgame.service.MatchEffectValueHelper.toBoolean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchTriggeredEffectResolutionService {

    private final MatchEffectDamageService matchEffectDamageService;
    private final MatchTriggeredCardEffectService matchTriggeredCardEffectService;
    private final MatchTriggeredGiftResolutionService matchTriggeredGiftResolutionService;

    public MatchTriggeredEffectResolutionService(
        MatchEffectDamageService matchEffectDamageService,
        MatchTriggeredCardEffectService matchTriggeredCardEffectService,
        MatchTriggeredGiftResolutionService matchTriggeredGiftResolutionService
    ) {
        this.matchEffectDamageService = matchEffectDamageService;
        this.matchTriggeredCardEffectService = matchTriggeredCardEffectService;
        this.matchTriggeredGiftResolutionService = matchTriggeredGiftResolutionService;
    }

    public Map<String, Object> applyCollabPostTriggeredEffectsAfterConfirm(
        Long matchId,
        Long userId,
        String sourceCardId,
        Long sourceCardInstanceId,
        int turnNumber,
        boolean hasCollabEffect,
        List<Map<String, Object>> giftTriggers,
        List<Long> selectedCardInstanceIds,
        Long selectionGiftHolderCardInstanceId
    ) {
        Map<String, Object> collabSummary = null;
        if (hasCollabEffect) {
            collabSummary = matchTriggeredCardEffectService.applyCollabTriggeredEffects(
                matchId,
                userId,
                sourceCardId,
                sourceCardInstanceId
            );
        }

        Map<String, Object> giftSummary = null;
        if (giftTriggers != null && !giftTriggers.isEmpty()) {
            giftSummary = matchTriggeredGiftResolutionService.applyGiftTriggeredEffectsFromContext(
                matchId,
                userId,
                sourceCardInstanceId,
                turnNumber,
                giftTriggers,
                "GIFT",
                selectedCardInstanceIds,
                selectionGiftHolderCardInstanceId
            );
        }

        List<Map<String, Object>> executedEffects = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();
        List<String> unsupportedEffects = new ArrayList<>();
        List<Map<String, Object>> triggeredGifts = new ArrayList<>();

        collectTriggeredEffectSummary(collabSummary, executedEffects, skippedEffects, unsupportedEffects, null);
        collectTriggeredEffectSummary(giftSummary, executedEffects, skippedEffects, unsupportedEffects, triggeredGifts);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceActionType", "COLLAB");
        result.put("collabEffect", collabSummary);
        result.put("gift", giftSummary);
        result.put("triggeredGifts", triggeredGifts);
        result.put("executedEffects", executedEffects);
        result.put("skippedEffects", skippedEffects);
        result.put("unsupportedEffects", unsupportedEffects);
        result.put("partiallyResolved", !skippedEffects.isEmpty() || !unsupportedEffects.isEmpty());
        result.put("applied", collabSummary != null || !triggeredGifts.isEmpty());
        return result;
    }

    public Map<String, Object> applyAttackArtPostTriggeredEffectsAfterConfirm(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        int turnNumber,
        List<Map<String, Object>> giftTriggers,
        List<Long> selectedCardInstanceIds,
        Long selectionGiftHolderCardInstanceId,
        Map<String, Object> downEventContext
    ) {
        List<Map<String, Object>> executedEffects = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();
        List<String> unsupportedEffects = new ArrayList<>();
        List<Map<String, Object>> triggeredGifts = new ArrayList<>();

        Map<String, Object> downEventResult = null;
        if (downEventContext != null) {
            Long downedOwnerUserId = asLong(downEventContext.get("downedOwnerUserId"));
            String downedCardId = asText(downEventContext.get("downedCardId"));
            String downedStageZone = asText(downEventContext.get("downedStageZone"));
            int downEventTurn = asInt(downEventContext.get("turnNumber"));
            downEventResult = matchEffectDamageService.applyDownEventEffect(
                matchId,
                userId,
                downedOwnerUserId,
                downedCardId,
                downEventTurn <= 0 ? turnNumber : downEventTurn,
                downedStageZone
            );
            if (downEventResult != null && !downEventResult.isEmpty()) {
                executedEffects.add(downEventResult);
            }
        }

        Map<String, Object> giftSummary = null;
        if (giftTriggers != null && !giftTriggers.isEmpty()) {
            giftSummary = matchTriggeredGiftResolutionService.applyGiftTriggeredEffectsFromContext(
                matchId,
                userId,
                sourceCardInstanceId,
                turnNumber,
                giftTriggers,
                "ATTACK_ART_POST_TRIGGER",
                selectedCardInstanceIds,
                selectionGiftHolderCardInstanceId
            );
            collectTriggeredEffectSummary(giftSummary, executedEffects, skippedEffects, unsupportedEffects, triggeredGifts);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceActionType", "ATTACK_ART_POST_TRIGGER");
        result.put("downEvent", downEventResult);
        result.put("gift", giftSummary);
        result.put("triggeredGifts", triggeredGifts);
        result.put("executedEffects", executedEffects);
        result.put("skippedEffects", skippedEffects);
        result.put("unsupportedEffects", unsupportedEffects);
        result.put("partiallyResolved", !skippedEffects.isEmpty() || !unsupportedEffects.isEmpty());
        result.put("applied", downEventResult != null || !triggeredGifts.isEmpty());
        return result;
    }

    public Map<String, Object> applyEffectPostTriggeredEffectsAfterConfirm(
        Long matchId,
        Long userId,
        int turnNumber,
        String originSourceActionType,
        Map<String, Object> downEventContext
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceActionType", "EFFECT_POST_TRIGGER");
        result.put("originSourceActionType", originSourceActionType);

        if (downEventContext == null || downEventContext.isEmpty()) {
            result.put("applied", false);
            result.put("reason", "NO_DOWN_EVENT_CONTEXT");
            result.put("executedEffects", List.of());
            result.put("skippedEffects", List.of());
            result.put("unsupportedEffects", List.of());
            return result;
        }

        Long downedOwnerUserId = asLong(downEventContext.get("downedOwnerUserId"));
        String downedCardId = asText(downEventContext.get("downedCardId"));
        String downedStageZone = asText(downEventContext.get("downedStageZone"));
        int downEventTurn = asInt(downEventContext.get("turnNumber"));
        Map<String, Object> downEventResult = matchEffectDamageService.applyDownEventEffect(
            matchId,
            userId,
            downedOwnerUserId,
            downedCardId,
            downEventTurn <= 0 ? turnNumber : downEventTurn,
            downedStageZone
        );

        result.put("downEvent", downEventResult);
        result.put("executedEffects", downEventResult == null ? List.of() : List.of(downEventResult));
        result.put("skippedEffects", List.of());
        result.put("unsupportedEffects", List.of());
        result.put("lifeReduced", downEventResult != null && toBoolean(downEventResult.get("lifeReduced")));
        result.put("applied", downEventResult != null && toBoolean(downEventResult.get("triggered")));
        return result;
    }

    private void collectTriggeredEffectSummary(
        Map<String, Object> summary,
        List<Map<String, Object>> executedEffects,
        List<Map<String, Object>> skippedEffects,
        List<String> unsupportedEffects,
        List<Map<String, Object>> triggeredGifts
    ) {
        if (summary == null || summary.isEmpty()) {
            return;
        }
        Object summaryExecutedEffects = summary.get("executedEffects");
        if (summaryExecutedEffects instanceof List<?> effects) {
            for (Object effect : effects) {
                if (effect instanceof Map<?, ?> effectMap) {
                    executedEffects.add(castToMap(effectMap));
                }
            }
        }
        Object summarySkippedEffects = summary.get("skippedEffects");
        if (summarySkippedEffects instanceof List<?> effects) {
            for (Object effect : effects) {
                if (effect instanceof Map<?, ?> effectMap) {
                    skippedEffects.add(castToMap(effectMap));
                }
            }
        }
        Object summaryUnsupportedEffects = summary.get("unsupportedEffects");
        if (summaryUnsupportedEffects instanceof List<?> effectTypes) {
            for (Object effectType : effectTypes) {
                String normalizedEffectType = normalize(effectType);
                if (StringUtils.hasText(normalizedEffectType) && !unsupportedEffects.contains(normalizedEffectType)) {
                    unsupportedEffects.add(normalizedEffectType);
                }
            }
        }
        if (triggeredGifts == null) {
            return;
        }
        Object gifts = summary.get("triggeredGifts");
        if (gifts instanceof List<?> list) {
            for (Object gift : list) {
                if (gift instanceof Map<?, ?> map) {
                    triggeredGifts.add(castToMap(map));
                }
            }
        }
    }

    private Map<String, Object> castToMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
