package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
public class BloomEffectResolutionService {

    private static final String INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM = "TRIGGER_EFFECT_CONFIRM";
    private static final String PENDING_STATUS = "PENDING";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MatchTriggeredCardEffectService matchTriggeredCardEffectService;
    private final MatchEventHookService matchEventHookService;

    public BloomEffectResolutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchTriggeredCardEffectService matchTriggeredCardEffectService,
        MatchEventHookService matchEventHookService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.matchTriggeredCardEffectService = matchTriggeredCardEffectService;
        this.matchEventHookService = matchEventHookService;
    }

    public BloomEffectResolution resolveAfterBloom(
        Long matchId,
        Long userId,
        int turnNumber,
        Long bloomCardInstanceId,
        String bloomCardId,
        BloomTargetSnapshot target
    ) {
        Map<String, Object> passiveGiftSummary = matchTriggeredCardEffectService.applyPassiveGiftExtraBloomAllowanceOnBloom(
            matchId,
            userId,
            target.holomemId(),
            bloomCardInstanceId,
            bloomCardId
        );
        MatchEffectService.TriggeredEffectPreview bloomPreview = matchTriggeredCardEffectService.previewBloomTriggeredEffect(
            matchId,
            userId,
            bloomCardId,
            bloomCardInstanceId,
            target.topLevelType()
        );
        Map<String, Object> bloomEffectSummary = buildTriggeredEffectDeferredSummary(bloomPreview);
        Map<String, Object> triggerSummary = matchEventHookService.onHolomemBloom(
            matchId,
            userId,
            bloomCardId,
            bloomCardInstanceId,
            target.topCardInstanceId(),
            target.zone()
        );
        BloomFollowupDecision triggerConfirmDecision = null;
        if (bloomPreview.hasEffect()) {
            Map<String, Object> additionalContext = new LinkedHashMap<>();
            additionalContext.put("sourceLevelType", target.topLevelType());
            triggerConfirmDecision = createTriggeredEffectConfirmPendingInteraction(
                matchId,
                userId,
                bloomCardInstanceId,
                bloomCardId,
                buildTriggeredEffectConfirmMessage(bloomPreview),
                List.of(buildInteractionSourceCardPayload(matchId, userId, bloomCardInstanceId, bloomCardId)),
                turnNumber,
                additionalContext
            );
        }
        return new BloomEffectResolution(
            passiveGiftSummary,
            bloomEffectSummary,
            triggerSummary,
            triggerConfirmDecision == null ? null : triggerConfirmDecision.decisionId(),
            triggerConfirmDecision == null ? null : triggerConfirmDecision.decisionType(),
            buildTriggeredResolutionOrder(bloomEffectSummary, triggerSummary),
            bloomPreview.hasEffect()
        );
    }

    private BloomFollowupDecision createTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        String message,
        List<Map<String, Object>> cards,
        int turnNumber,
        Map<String, Object> additionalContext
    ) {
        if (hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的互動，請先完成確認");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
        context.put("sourceActionType", "BLOOM");
        context.put("title", "確認 Bloom 效果");
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
            "BLOOM",
            sourceCardInstanceId,
            sourceCardId,
            "BLOOM_EFFECT",
            0,
            0,
            PENDING_STATUS,
            toJson(context)
        );
        if (decisionId == null) {
            return null;
        }
        return new BloomFollowupDecision(decisionId, INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
    }

    private Map<String, Object> buildInteractionSourceCardPayload(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String fallbackCardId
    ) {
        Map<String, Object> card = loadCardCandidateForDecision(
            matchId,
            userId,
            userId,
            cardInstanceId,
            "STAGE",
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
                value.put("zone", normalize(rs.getString("zone")));
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
        fallback.put("zone", normalize(fallbackZone));
        fallback.put("name", null);
        fallback.put("cardType", null);
        fallback.put("imageUrl", null);
        fallback.put("levelType", null);
        return fallback;
    }

    private Map<String, Object> buildTriggeredEffectDeferredSummary(
        MatchEffectService.TriggeredEffectPreview preview
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        boolean hasEffect = preview != null && preview.hasEffect();
        summary.put("hasBloomEffect", hasEffect);
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

    private String buildTriggeredEffectConfirmMessage(MatchEffectService.TriggeredEffectPreview preview) {
        String rawText = preview == null ? null : preview.rawText();
        List<String> effectTypes = preview == null ? List.of() : preview.effectTypes();
        String effectSummary = effectTypes == null || effectTypes.isEmpty()
            ? "無可解析效果類型"
            : String.join("、", effectTypes);
        if (!StringUtils.hasText(rawText)) {
            return "是否要執行此 BLOOM 特殊效果？\n效果類型：" + effectSummary;
        }
        return "是否要執行此 BLOOM 特殊效果？\n能力文本：" + rawText + "\n效果類型：" + effectSummary;
    }

    private List<Map<String, Object>> buildTriggeredResolutionOrder(
        Map<String, Object> bloomEffectSummary,
        Map<String, Object> triggerSummary
    ) {
        List<Map<String, Object>> order = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("step", "BLOOM_EFFECT");
        first.put("priority", 100);
        first.put("applied", bloomEffectSummary != null);
        order.add(first);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("step", "BLOOM_EVENT_HOOK");
        second.put("priority", 200);
        second.put("applied", triggerSummary != null);
        order.add(second);
        return order;
    }

    private boolean hasBlockingPendingDecision(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = ?
            """,
            Integer.class,
            matchId,
            userId,
            PENDING_STATUS
        );
        return count != null && count > 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("無法序列化 BLOOM effect payload", ex);
        }
    }

    private record BloomFollowupDecision(Long decisionId, String decisionType) {
    }
}
