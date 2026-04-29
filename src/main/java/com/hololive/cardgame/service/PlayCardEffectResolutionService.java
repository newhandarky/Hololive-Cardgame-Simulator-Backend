package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlayCardEffectResolutionService {

    private final MatchGiftTriggerService matchGiftTriggerService;
    private final MatchEventHookService matchEventHookService;
    private final FollowupSourceCardPayloadBuilder followupSourceCardPayloadBuilder;
    private final FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter;
    private final GiftTriggerPendingPayloadBuilder giftTriggerPendingPayloadBuilder;
    private final GiftSelectionPendingContextBuilder giftSelectionPendingContextBuilder;
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder;

    public PlayCardEffectResolutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchGiftTriggerService matchGiftTriggerService,
        MatchEventHookService matchEventHookService
    ) {
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.matchEventHookService = matchEventHookService;
        this.followupSourceCardPayloadBuilder = new FollowupSourceCardPayloadBuilder(jdbcTemplate);
        this.followupTriggerConfirmPendingDecisionWriter = new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, objectMapper);
        this.giftTriggerPendingPayloadBuilder = new GiftTriggerPendingPayloadBuilder();
        this.giftSelectionPendingContextBuilder = new GiftSelectionPendingContextBuilder();
        this.giftTriggeredEffectDeferredSummaryBuilder = new GiftTriggeredEffectDeferredSummaryBuilder();
    }

    public PlayCardEffectResolution resolve(PlayCardAction action, PlayCardResolutionResult resolutionResult) {
        if (action == null || resolutionResult == null) {
            throw new IllegalArgumentException("PLAY_CARD effect 結算缺少必要上下文");
        }

        Long matchId = action.matchId();
        Long userId = action.actorUserId();
        if (resolutionResult.openingReset()) {
            return new PlayCardEffectResolution(
                Map.of("deferredUntilLiveStart", true),
                List.of(),
                Map.of(),
                null,
                null,
                true,
                List.of()
            );
        }

        Map<String, Object> triggerSummary = matchEventHookService.onHolomemEnter(
            matchId,
            userId,
            resolutionResult.cardId(),
            resolutionResult.cardInstanceId(),
            resolutionResult.targetZone()
        );
        List<Map<String, Object>> giftTriggeredEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnStageEnter(
            matchId,
            userId,
            resolutionResult.cardInstanceId(),
            resolutionResult.targetZone(),
            resolutionResult.turnNumber()
        );
        Map<String, Object> giftEffectSummary = buildGiftTriggeredEffectDeferredSummary(giftTriggeredEffects);

        FollowupInteractionDecision pendingDecision = null;
        if (!giftTriggeredEffects.isEmpty()) {
            pendingDecision = createGiftTriggeredEffectConfirmPendingInteraction(
                matchId,
                userId,
                resolutionResult.cardInstanceId(),
                resolutionResult.cardId(),
                List.of(
                    followupSourceCardPayloadBuilder.buildOwnedCard(
                        matchId,
                        userId,
                        resolutionResult.cardInstanceId(),
                        resolutionResult.targetZone(),
                        resolutionResult.cardId()
                    )
                ),
                giftTriggeredEffects,
                resolutionResult.turnNumber()
            );
        }

        return new PlayCardEffectResolution(
            triggerSummary,
            giftTriggeredEffects,
            giftEffectSummary,
            pendingDecision == null ? null : pendingDecision.decisionId(),
            pendingDecision == null ? null : pendingDecision.decisionType(),
            false,
            buildTriggeredResolutionOrder(
                "GIFT_TRIGGER",
                100,
                giftEffectSummary,
                "ENTER_EVENT_HOOK",
                200,
                triggerSummary
            )
        );
    }

    private Map<String, Object> buildGiftTriggeredEffectDeferredSummary(List<Map<String, Object>> giftTriggeredEffects) {
        return giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(giftTriggeredEffects);
    }

    private FollowupInteractionDecision createGiftTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> cards,
        List<Map<String, Object>> giftTriggeredEffects,
        int turnNumber
    ) {
        List<Map<String, Object>> giftTriggers = buildGiftTriggerPayloads(giftTriggeredEffects);
        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("giftTriggers", giftTriggers);
        additionalContext.put("giftCount", giftTriggers.size());
        appendGiftSelectionPendingContext(additionalContext, giftTriggeredEffects);

        return followupTriggerConfirmPendingDecisionWriter.create(new FollowupTriggerConfirmPendingDecisionInput(
            matchId,
            userId,
            "GIFT",
            sourceCardInstanceId,
            sourceCardId,
            "GIFT_TRIGGER",
            "確認 Gift 效果",
            buildGiftTriggeredEffectConfirmMessage(giftTriggeredEffects),
            cards,
            turnNumber,
            additionalContext
        ));
    }

    private List<Map<String, Object>> buildGiftTriggerPayloads(List<Map<String, Object>> giftTriggeredEffects) {
        return giftTriggerPendingPayloadBuilder.buildGiftTriggerPayloads(giftTriggeredEffects);
    }

    private void appendGiftSelectionPendingContext(
        Map<String, Object> additionalContext,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        if (additionalContext == null) {
            return;
        }
        additionalContext.putAll(giftSelectionPendingContextBuilder.buildSelectionPendingContext(giftTriggeredEffects));
    }

    private String buildGiftTriggeredEffectConfirmMessage(List<Map<String, Object>> giftTriggeredEffects) {
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return "是否要執行本次 Gift 觸發效果？";
        }
        return "是否要執行本次 Gift 觸發效果？\n" + buildGiftTriggeredEffectDetails(giftTriggeredEffects);
    }

    private String buildGiftTriggeredEffectDetails(List<Map<String, Object>> giftTriggeredEffects) {
        int count = 0;
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> trigger : giftTriggeredEffects) {
            count++;
            String cardId = asString(trigger.get("giftHolderCardId"));
            String triggerType = normalizeZone(trigger.get("triggerType"));
            String rawText = asString(trigger.get("rawText"));
            List<String> effectTypes = toStringList(trigger.get("requestedEffects"));
            String effectSummary = effectTypes.isEmpty() ? "無可解析效果類型" : String.join("、", effectTypes);
            StringBuilder line = new StringBuilder();
            line.append("#").append(count).append(" ");
            if (StringUtils.hasText(cardId)) {
                line.append(cardId).append(" ");
            }
            line.append("[").append(StringUtils.hasText(triggerType) ? triggerType : "GIFT").append("]");
            line.append(" 效果類型：").append(effectSummary);
            if (StringUtils.hasText(rawText)) {
                line.append("\n").append(rawText);
            }
            lines.add(line.toString());
        }
        return String.join("\n\n", lines);
    }

    private List<Map<String, Object>> buildTriggeredResolutionOrder(
        String firstStep,
        int firstPriority,
        Map<String, Object> firstSummary,
        String secondStep,
        int secondPriority,
        Map<String, Object> secondSummary
    ) {
        List<Map<String, Object>> order = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("step", firstStep);
        first.put("priority", firstPriority);
        first.put("applied", firstSummary != null);
        order.add(first);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("step", secondStep);
        second.put("priority", secondPriority);
        second.put("applied", secondSummary != null);
        order.add(second);
        return order;
    }

    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String text = asString(item);
                if (StringUtils.hasText(text)) {
                    result.add(text);
                }
            }
            return result;
        }
        return List.of();
    }
}
