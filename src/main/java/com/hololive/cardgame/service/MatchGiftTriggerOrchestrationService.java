package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftExecutionSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchGiftTriggerOrchestrationService {

    private final JdbcTemplate jdbcTemplate;
    private final MatchGiftTriggerSummaryService giftTriggerSummaryService;
    private final EffectTextParser effectTextParser;

    MatchGiftTriggerOrchestrationService(
        JdbcTemplate jdbcTemplate,
        MatchGiftTriggerSummaryService giftTriggerSummaryService,
        EffectTextParser effectTextParser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.giftTriggerSummaryService = giftTriggerSummaryService;
        this.effectTextParser = effectTextParser;
    }

    <T> List<Map<String, Object>> buildTriggeredSummaries(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String normalizedTriggerType,
        T sourceContext,
        GiftSourceContextResolver<T> sourceContextResolver,
        GiftHolderSummaryResolver<T> summaryResolver
    ) {
        if (matchId == null || userId == null) {
            return List.of();
        }
        List<Map<String, Object>> holders = jdbcTemplate.queryForList(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   h.zone,
                   h.current_level,
                   m.passive_effect_json::text AS passive_text
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
            ORDER BY h.id
            """,
            matchId,
            userId
        );
        if (holders.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> triggered = new ArrayList<>();
        for (Map<String, Object> holder : holders) {
            Long effectiveSourceCardInstanceId = sourceCardInstanceId == null
                ? MatchEffectValueHelper.asLong(holder.get("match_card_id"))
                : sourceCardInstanceId;
            T effectiveSourceContext = sourceContext == null
                ? sourceContextResolver.resolve(matchId, effectiveSourceCardInstanceId, holder)
                : sourceContext;
            Map<String, Object> summary = summaryResolver.resolve(
                holder,
                effectiveSourceCardInstanceId,
                effectiveSourceContext,
                normalizedTriggerType
            );
            if (summary != null) {
                triggered.add(summary);
            }
        }
        return triggered;
    }

    Map<String, Object> loadGiftHolder(Long matchId, Long userId, Long giftHolderHolomemId) {
        if (matchId == null || userId == null || giftHolderHolomemId == null || giftHolderHolomemId <= 0) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   h.zone,
                   h.current_level,
                   m.passive_effect_json::text AS passive_text
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("holomem_id", rs.getLong("holomem_id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                row.put("current_level", rs.getString("current_level"));
                row.put("passive_text", rs.getString("passive_text"));
                return row;
            },
            matchId,
            userId,
            giftHolderHolomemId
        );
    }

    Map<String, Object> buildTriggerSummary(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        String normalizedTriggerType,
        String giftText,
        Map<String, Object> holder,
        boolean executeEffects,
        GiftExecutionResolver executionResolver,
        GiftSelectionPreviewResolver selectionPreviewResolver
    ) {
        Long holderHolomemId = MatchEffectValueHelper.asLong(holder.get("holomem_id"));
        Long holderCardInstanceId = MatchEffectValueHelper.asLong(holder.get("match_card_id"));
        String holderZone = MatchEffectValueHelper.normalize(MatchEffectValueHelper.asText(holder.get("zone")));
        GiftExecutionSummary execution = executionResolver.resolve(
            matchId,
            userId,
            holderCardInstanceId,
            triggerTargetCardInstanceId,
            giftText,
            executeEffects
        );
        Map<String, Object> summary = giftTriggerSummaryService.buildTriggerSummary(
            normalizedTriggerType,
            holderHolomemId,
            holderCardInstanceId,
            MatchEffectValueHelper.asText(holder.get("card_id")),
            holderZone,
            sourceCardInstanceId,
            triggerTargetCardInstanceId,
            giftText,
            execution,
            !executeEffects,
            holder
        );
        if (!executeEffects) {
            appendSelectionPreviewContext(
                summary,
                matchId,
                userId,
                giftText,
                holder,
                selectionPreviewResolver
            );
        }
        return summary;
    }

    private void appendSelectionPreviewContext(
        Map<String, Object> summary,
        Long matchId,
        Long userId,
        String giftText,
        Map<String, Object> storedTriggerContext,
        GiftSelectionPreviewResolver selectionPreviewResolver
    ) {
        if (summary == null || summary.isEmpty() || matchId == null || userId == null || !StringUtils.hasText(giftText)) {
            return;
        }
        for (String effectType : MatchEffectValueHelper.toTextList(summary.get("requestedEffects"))) {
            GiftSelectionPreview preview = selectionPreviewResolver.resolve(
                matchId,
                userId,
                effectType,
                giftText,
                storedTriggerContext
            );
            if (preview == null || preview.candidates().isEmpty()) {
                continue;
            }
            int maxSelect = Math.min(preview.requestedCount(), preview.candidates().size());
            if (maxSelect <= 0 || preview.candidates().size() <= maxSelect) {
                continue;
            }
            summary.put("selectionRequired", true);
            summary.put("selectionEffectType", effectTextParser.normalizeEffectType(preview.effectType()));
            summary.put("selectionMinSelect", 1);
            summary.put("selectionMaxSelect", maxSelect);
            List<Long> candidateCardInstanceIds = preview.candidates().stream()
                .map(MatchEffectService.DecisionCandidate::cardInstanceId)
                .filter(id -> id != null && id > 0)
                .toList();
            summary.put("selectionCandidateCardInstanceIds", candidateCardInstanceIds);
            summary.put("selectionCandidates", preview.candidates());
            break;
        }
    }

    @FunctionalInterface
    interface GiftExecutionResolver {

        GiftExecutionSummary resolve(
            Long matchId,
            Long userId,
            Long holderCardInstanceId,
            Long triggerTargetCardInstanceId,
            String giftText,
            boolean executeEffects
        );
    }

    @FunctionalInterface
    interface GiftSelectionPreviewResolver {

        GiftSelectionPreview resolve(
            Long matchId,
            Long userId,
            String effectType,
            String giftText,
            Map<String, Object> storedTriggerContext
        );
    }

    @FunctionalInterface
    interface GiftSourceContextResolver<T> {

        T resolve(Long matchId, Long effectiveSourceCardInstanceId, Map<String, Object> holder);
    }

    @FunctionalInterface
    interface GiftHolderSummaryResolver<T> {

        Map<String, Object> resolve(
            Map<String, Object> holder,
            Long effectiveSourceCardInstanceId,
            T effectiveSourceContext,
            String normalizedTriggerType
        );
    }

    record GiftSelectionPreview(
        String effectType,
        int requestedCount,
        List<MatchEffectService.DecisionCandidate> candidates
    ) {}
}
