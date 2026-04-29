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
public class CollabEffectResolutionService {

    private final MatchTriggeredCardEffectService matchTriggeredCardEffectService;
    private final MatchGiftTriggerService matchGiftTriggerService;
    private final MatchEventHookService matchEventHookService;
    private final GiftTriggerInteractionCardsBuilder giftTriggerInteractionCardsBuilder;
    private final FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter;
    private final GiftTriggerPendingPayloadBuilder giftTriggerPendingPayloadBuilder;
    private final GiftSelectionPendingContextBuilder giftSelectionPendingContextBuilder;
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder;
    private final GiftTriggeredEffectDetailsMessageBuilder giftTriggeredEffectDetailsMessageBuilder;

    public CollabEffectResolutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchTriggeredCardEffectService matchTriggeredCardEffectService,
        MatchGiftTriggerService matchGiftTriggerService,
        MatchEventHookService matchEventHookService
    ) {
        this.matchTriggeredCardEffectService = matchTriggeredCardEffectService;
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.matchEventHookService = matchEventHookService;
        this.giftTriggerInteractionCardsBuilder = new GiftTriggerInteractionCardsBuilder(jdbcTemplate);
        this.followupTriggerConfirmPendingDecisionWriter = new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, objectMapper);
        this.giftTriggerPendingPayloadBuilder = new GiftTriggerPendingPayloadBuilder();
        this.giftSelectionPendingContextBuilder = new GiftSelectionPendingContextBuilder();
        this.giftTriggeredEffectDeferredSummaryBuilder = new GiftTriggeredEffectDeferredSummaryBuilder();
        this.giftTriggeredEffectDetailsMessageBuilder = new GiftTriggeredEffectDetailsMessageBuilder();
    }

    public CollabEffectResolution resolve(CollabAction action, CollabResolutionResult resolutionResult) {
        if (action == null || resolutionResult == null) {
            throw new IllegalArgumentException("COLLAB effect 結算缺少必要上下文");
        }

        MatchEffectService.TriggeredEffectPreview collabPreview = matchTriggeredCardEffectService.previewCollabTriggeredEffect(
            action.matchId(),
            action.actorUserId(),
            resolutionResult.sourceCardId(),
            resolutionResult.sourceCardInstanceId()
        );
        Map<String, Object> collabEffectSummary = buildTriggeredEffectDeferredSummary(collabPreview);

        List<Map<String, Object>> giftTriggeredEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnCollab(
            action.matchId(),
            action.actorUserId(),
            resolutionResult.sourceCardInstanceId(),
            resolutionResult.turnNumber()
        );
        Map<String, Object> giftEffectSummary = giftTriggeredEffects.isEmpty()
            ? null
            : buildGiftTriggeredEffectDeferredSummary(giftTriggeredEffects);

        Map<String, Object> triggerSummary = matchEventHookService.onHolomemCollab(
            action.matchId(),
            action.actorUserId(),
            resolutionResult.sourceCardId(),
            resolutionResult.sourceCardInstanceId()
        );

        FollowupInteractionDecision pendingDecision = null;
        if (collabPreview.hasEffect() || !giftTriggeredEffects.isEmpty()) {
            pendingDecision = createCollabTriggeredEffectConfirmPendingInteraction(
                action.matchId(),
                action.actorUserId(),
                resolutionResult.sourceCardInstanceId(),
                resolutionResult.sourceCardId(),
                collabPreview,
                giftTriggeredEffects,
                resolutionResult.turnNumber()
            );
        }

        return new CollabEffectResolution(
            collabPreview,
            collabEffectSummary,
            giftTriggeredEffects,
            giftEffectSummary,
            triggerSummary,
            pendingDecision == null ? null : pendingDecision.decisionId(),
            pendingDecision == null ? null : pendingDecision.decisionType(),
            buildTriggeredResolutionOrder(
                "COLLAB_TRIGGER",
                100,
                mergeEffectSummaryForChecks(
                    collabEffectSummary,
                    giftEffectSummary == null ? List.of() : List.of(giftEffectSummary)
                ),
                "COLLAB_EVENT_HOOK",
                200,
                triggerSummary
            )
        );
    }

    private Map<String, Object> buildTriggeredEffectDeferredSummary(
        MatchEffectService.TriggeredEffectPreview preview
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        boolean hasEffect = preview != null && preview.hasEffect();
        summary.put("hasCollabEffect", hasEffect);
        summary.put("deferred", hasEffect);
        summary.put("requestedEffects", preview == null || preview.effectTypes() == null ? List.of() : preview.effectTypes());
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        summary.put("rawText", preview == null ? null : preview.rawText());
        if (preview != null && preview.diceRoll() != null) {
            summary.put("diceRoll", preview.diceRoll());
        }
        return summary;
    }

    private Map<String, Object> buildGiftTriggeredEffectDeferredSummary(List<Map<String, Object>> giftTriggeredEffects) {
        return giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(giftTriggeredEffects);
    }

    private FollowupInteractionDecision createCollabTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        MatchEffectService.TriggeredEffectPreview collabPreview,
        List<Map<String, Object>> giftTriggeredEffects,
        int turnNumber
    ) {
        List<Map<String, Object>> cards = giftTriggerInteractionCardsBuilder.buildGiftTriggerInteractionCards(
            matchId,
            userId,
            sourceCardInstanceId,
            sourceCardId,
            giftTriggeredEffects
        );
        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("hasCollabEffect", collabPreview != null && collabPreview.hasEffect());

        List<Map<String, Object>> giftTriggers = buildGiftTriggerPayloads(giftTriggeredEffects);
        additionalContext.put("giftTriggers", giftTriggers);
        additionalContext.put("giftCount", giftTriggers.size());
        appendGiftSelectionPendingContext(additionalContext, giftTriggeredEffects);
        additionalContext.put("triggerSections", buildCollabTriggerSections(collabPreview, giftTriggeredEffects));

        return followupTriggerConfirmPendingDecisionWriter.create(new FollowupTriggerConfirmPendingDecisionInput(
            matchId,
            userId,
            "COLLAB",
            sourceCardInstanceId,
            sourceCardId,
            "COLLAB_TRIGGER",
            "確認連動觸發效果",
            buildCollabTriggeredEffectConfirmMessage(collabPreview, giftTriggeredEffects),
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

    private String buildCollabTriggeredEffectConfirmMessage(
        MatchEffectService.TriggeredEffectPreview collabPreview,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        List<String> lines = new ArrayList<>();
        if (collabPreview != null && collabPreview.hasEffect()) {
            lines.add("[Collab]\n" + buildTriggeredEffectConfirmMessage(collabPreview));
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            lines.add("[Gift]\n" + buildGiftTriggeredEffectDetails(giftTriggeredEffects));
        }
        if (lines.isEmpty()) {
            return "是否要執行本次連動觸發效果？";
        }
        return "是否要執行本次連動觸發效果？\n" + String.join("\n\n", lines);
    }

    private String buildTriggeredEffectConfirmMessage(MatchEffectService.TriggeredEffectPreview preview) {
        String rawText = preview == null ? null : preview.rawText();
        List<String> effectTypes = preview == null ? List.of() : preview.effectTypes();
        String effectSummary = effectTypes == null || effectTypes.isEmpty()
            ? "無可解析效果類型"
            : String.join("、", effectTypes);
        if (!StringUtils.hasText(rawText)) {
            return "是否要執行此 連動 特殊效果？\n效果類型：" + effectSummary;
        }
        return "是否要執行此 連動 特殊效果？\n能力文本：" + rawText + "\n效果類型：" + effectSummary;
    }

    private List<Map<String, Object>> buildCollabTriggerSections(
        MatchEffectService.TriggeredEffectPreview collabPreview,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        List<Map<String, Object>> sections = new ArrayList<>();
        if (collabPreview != null && collabPreview.hasEffect()) {
            Map<String, Object> collabSection = new LinkedHashMap<>();
            collabSection.put("sectionType", "COLLAB_EFFECT");
            collabSection.put("title", "Collab");
            collabSection.put("effectTypes", collabPreview.effectTypes() == null ? List.of() : collabPreview.effectTypes());
            collabSection.put("rawText", collabPreview.rawText());
            sections.add(collabSection);
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            List<Map<String, Object>> giftItems = new ArrayList<>();
            for (Map<String, Object> trigger : giftTriggeredEffects) {
                if (trigger == null || trigger.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("triggerType", normalizeZone(trigger.get("triggerType")));
                item.put("giftHolderCardId", asString(trigger.get("giftHolderCardId")));
                item.put("rawText", asString(trigger.get("rawText")));
                item.put("requestedEffects", toStringList(trigger.get("requestedEffects")));
                giftItems.add(item);
            }
            if (!giftItems.isEmpty()) {
                Map<String, Object> giftSection = new LinkedHashMap<>();
                giftSection.put("sectionType", "GIFT");
                giftSection.put("title", "Gift");
                giftSection.put("count", giftItems.size());
                giftSection.put("items", giftItems);
                sections.add(giftSection);
            }
        }
        return sections;
    }

    private String buildGiftTriggeredEffectDetails(List<Map<String, Object>> giftTriggeredEffects) {
        return giftTriggeredEffectDetailsMessageBuilder.buildGiftTriggeredEffectDetails(giftTriggeredEffects);
    }

    private Map<String, Object> mergeEffectSummaryForChecks(
        Map<String, Object> primary,
        List<Map<String, Object>> additionalEffects
    ) {
        if ((additionalEffects == null || additionalEffects.isEmpty()) && primary != null) {
            return primary;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        List<Object> executed = new ArrayList<>();
        if (primary != null) {
            executed.add(primary);
        }
        if (additionalEffects != null) {
            executed.addAll(additionalEffects);
        }
        merged.put("executedEffects", executed);
        return merged;
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
