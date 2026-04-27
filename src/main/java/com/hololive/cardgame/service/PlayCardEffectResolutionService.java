package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlayCardEffectResolutionService {

    private static final String INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM = "TRIGGER_EFFECT_CONFIRM";
    private static final String PENDING_STATUS = "PENDING";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MatchGiftTriggerService matchGiftTriggerService;
    private final MatchEventHookService matchEventHookService;

    public PlayCardEffectResolutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchGiftTriggerService matchGiftTriggerService,
        MatchEventHookService matchEventHookService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.matchEventHookService = matchEventHookService;
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

        PendingInteractionDecision pendingDecision = null;
        if (!giftTriggeredEffects.isEmpty()) {
            pendingDecision = createGiftTriggeredEffectConfirmPendingInteraction(
                matchId,
                userId,
                resolutionResult.cardInstanceId(),
                resolutionResult.cardId(),
                List.of(
                    buildInteractionSourceCardPayload(
                        matchId,
                        userId,
                        resolutionResult.cardInstanceId(),
                        resolutionResult.cardId(),
                        resolutionResult.targetZone()
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
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> triggers = giftTriggeredEffects == null ? List.of() : giftTriggeredEffects;
        List<String> requestedEffects = new ArrayList<>();
        for (Map<String, Object> trigger : triggers) {
            Object requested = trigger.get("requestedEffects");
            if (!(requested instanceof List<?> list)) {
                continue;
            }
            for (Object effectType : list) {
                String normalized = normalizeZone(effectType);
                if (StringUtils.hasText(normalized) && !requestedEffects.contains(normalized)) {
                    requestedEffects.add(normalized);
                }
            }
        }
        summary.put("sourceActionType", "GIFT");
        summary.put("deferred", !triggers.isEmpty());
        summary.put("triggeredGifts", triggers);
        summary.put("requestedEffects", requestedEffects);
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        return summary;
    }

    private PendingInteractionDecision createGiftTriggeredEffectConfirmPendingInteraction(
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

        return createTriggeredEffectConfirmPendingInteraction(
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
        );
    }

    private PendingInteractionDecision createTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        String title,
        String message,
        List<Map<String, Object>> cards,
        int turnNumber,
        Map<String, Object> additionalContext
    ) {
        if (hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的互動，請先完成確認");
        }
        int minSelect = 0;
        int maxSelect = 0;
        if (additionalContext != null && !additionalContext.isEmpty()) {
            minSelect = Math.max(asInt(additionalContext.get("minSelect")), 0);
            maxSelect = Math.max(asInt(additionalContext.get("maxSelect")), minSelect);
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
        context.put("sourceActionType", normalizeZone(sourceActionType));
        context.put("title", title);
        context.put("message", message);
        context.put("cards", cards == null ? List.of() : cards);
        context.put("turnNumber", turnNumber);
        if (additionalContext != null && !additionalContext.isEmpty()) {
            context.putAll(additionalContext);
        }

        Long decisionId = jdbcTemplate.query(
            """
            INSERT INTO match_pending_decisions (
                match_id,
                user_id,
                decision_type,
                source_action_type,
                source_card_instance_id,
                source_card_id,
                effect_type,
                min_select,
                max_select,
                status,
                context_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM,
            normalizeZone(sourceActionType),
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            minSelect,
            maxSelect,
            PENDING_STATUS,
            toJson(context)
        );
        if (decisionId == null) {
            return null;
        }
        return new PendingInteractionDecision(decisionId, INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
    }

    private boolean hasBlockingPendingDecision(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
            """,
            Integer.class,
            matchId,
            userId
        );
        return count != null && count > 0;
    }

    private List<Map<String, Object>> buildGiftTriggerPayloads(List<Map<String, Object>> giftTriggeredEffects) {
        List<Map<String, Object>> giftTriggers = new ArrayList<>();
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return giftTriggers;
        }
        for (Map<String, Object> trigger : giftTriggeredEffects) {
            if (trigger == null || trigger.isEmpty()) {
                continue;
            }
            Map<String, Object> triggerPayload = new LinkedHashMap<>();
            triggerPayload.put("triggerType", normalizeZone(trigger.get("triggerType")));
            triggerPayload.put("sourceCardInstanceId", asLong(trigger.get("sourceCardInstanceId")));
            triggerPayload.put("triggerTargetCardInstanceId", asLong(trigger.get("triggerTargetCardInstanceId")));
            triggerPayload.put("giftHolderHolomemId", asLong(trigger.get("giftHolderHolomemId")));
            triggerPayload.put("giftHolderCardInstanceId", asLong(trigger.get("giftHolderCardInstanceId")));
            triggerPayload.put("giftHolderCardId", asString(trigger.get("giftHolderCardId")));
            triggerPayload.put("giftHolderZone", asString(trigger.get("giftHolderZone")));
            triggerPayload.put(
                "giftHolderAttachedCheerCardInstanceIds",
                toLongList(trigger.get("giftHolderAttachedCheerCardInstanceIds"))
            );
            triggerPayload.put("giftHolderAttachedCheerCardIds", toStringList(trigger.get("giftHolderAttachedCheerCardIds")));
            triggerPayload.put("giftHolderStackCardInstanceIds", toLongList(trigger.get("giftHolderStackCardInstanceIds")));
            triggerPayload.put("giftHolderStackCardIds", toStringList(trigger.get("giftHolderStackCardIds")));
            triggerPayload.put("selectionRequired", toBoolean(trigger.get("selectionRequired")));
            triggerPayload.put("selectionEffectType", asString(trigger.get("selectionEffectType")));
            triggerPayload.put("selectionMinSelect", asInt(trigger.get("selectionMinSelect")));
            triggerPayload.put("selectionMaxSelect", asInt(trigger.get("selectionMaxSelect")));
            triggerPayload.put(
                "selectionCandidateCardInstanceIds",
                toLongList(trigger.get("selectionCandidateCardInstanceIds"))
            );
            triggerPayload.put("rawText", asString(trigger.get("rawText")));
            giftTriggers.add(triggerPayload);
        }
        return giftTriggers;
    }

    private void appendGiftSelectionPendingContext(
        Map<String, Object> additionalContext,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        if (additionalContext == null || giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return;
        }
        List<Map<String, Object>> selectableTriggers = giftTriggeredEffects.stream()
            .filter(Objects::nonNull)
            .filter(trigger -> toBoolean(trigger.get("selectionRequired")))
            .toList();
        if (selectableTriggers.size() != 1) {
            return;
        }
        Map<String, Object> selectionTrigger = selectableTriggers.get(0);
        List<Long> candidateCardInstanceIds = toLongList(selectionTrigger.get("selectionCandidateCardInstanceIds"));
        if (candidateCardInstanceIds.isEmpty()) {
            return;
        }
        additionalContext.put("candidateCardInstanceIds", candidateCardInstanceIds);
        additionalContext.put("selectionGiftHolderCardInstanceId", asLong(selectionTrigger.get("giftHolderCardInstanceId")));
        additionalContext.put("minSelect", Math.max(asInt(selectionTrigger.get("selectionMinSelect")), 1));
        additionalContext.put(
            "maxSelect",
            Math.max(
                asInt(selectionTrigger.get("selectionMaxSelect")),
                Math.max(asInt(selectionTrigger.get("selectionMinSelect")), 1)
            )
        );
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

    private Map<String, Object> buildInteractionSourceCardPayload(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String cardId,
        String fallbackZone
    ) {
        return loadCardCandidateForDecision(matchId, userId, userId, cardInstanceId, fallbackZone, cardId);
    }

    private Map<String, Object> loadCardCandidateForDecision(
        Long matchId,
        Long viewerUserId,
        Long ownerUserId,
        Long cardInstanceId,
        String fallbackZone,
        String fallbackCardId
    ) {
        Map<String, Object> row = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   mc.zone,
                   c.name,
                   c.card_type,
                   c.image_url,
                   m.level_type
            FROM match_cards mc
            LEFT JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("cardInstanceId", rs.getLong("card_instance_id"));
                value.put("cardId", rs.getString("card_id"));
                value.put("zone", normalizeZone(rs.getString("zone")));
                value.put("name", rs.getString("name"));
                value.put("cardType", rs.getString("card_type"));
                value.put("imageUrl", rs.getString("image_url"));
                value.put("levelType", rs.getString("level_type"));
                return value;
            },
            matchId,
            ownerUserId,
            cardInstanceId
        );
        if (row != null) {
            if (!viewerUserId.equals(ownerUserId)) {
                row.put("zone", null);
            }
            return row;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("cardInstanceId", cardInstanceId);
        fallback.put("cardId", fallbackCardId);
        fallback.put("zone", normalizeZone(fallbackZone));
        fallback.put("name", null);
        fallback.put("cardType", null);
        fallback.put("imageUrl", null);
        fallback.put("levelType", null);
        return fallback;
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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("無法序列化效果確認內容", e);
        }
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

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
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
            return Integer.parseInt(String.valueOf(value));
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
        return Boolean.parseBoolean(String.valueOf(value));
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

    private List<Long> toLongList(Object value) {
        if (value instanceof List<?> list) {
            List<Long> result = new ArrayList<>();
            for (Object item : list) {
                Long parsed = asLong(item);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
            return result;
        }
        return List.of();
    }

    private record PendingInteractionDecision(
        Long decisionId,
        String decisionType
    ) {
    }
}
