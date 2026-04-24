package com.hololive.cardgame.service;

import static com.hololive.cardgame.service.MatchEffectValueHelper.asLong;
import static com.hololive.cardgame.service.MatchEffectValueHelper.asText;
import static com.hololive.cardgame.service.MatchEffectValueHelper.normalize;
import static com.hololive.cardgame.service.MatchEffectValueHelper.toBoolean;
import static com.hololive.cardgame.service.MatchEffectValueHelper.toLongList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchTriggeredGiftResolutionService {

    private final JdbcTemplate jdbcTemplate;
    private final MatchGiftTriggerService matchGiftTriggerService;

    public MatchTriggeredGiftResolutionService(
        JdbcTemplate jdbcTemplate,
        MatchGiftTriggerService matchGiftTriggerService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchGiftTriggerService = matchGiftTriggerService;
    }

    public Map<String, Object> applyGiftTriggeredEffectsFromContext(
        Long matchId,
        Long userId,
        Long defaultSourceCardInstanceId,
        int turnNumber,
        List<Map<String, Object>> giftTriggers,
        String sourceActionType,
        List<Long> selectedCardInstanceIds,
        Long selectionGiftHolderCardInstanceId
    ) {
        List<Long> effectiveSelectedCardInstanceIds = selectedCardInstanceIds == null ? List.of() : selectedCardInstanceIds;
        List<Map<String, Object>> triggeredGifts = new ArrayList<>();
        List<Map<String, Object>> aggregatedExecutedEffects = new ArrayList<>();
        List<Map<String, Object>> aggregatedSkippedEffects = new ArrayList<>();
        List<String> aggregatedUnsupportedEffects = new ArrayList<>();

        for (Map<String, Object> trigger : giftTriggers) {
            String triggerType = asText(trigger.get("triggerType"));
            Long sourceCardInstanceId = asLong(trigger.get("sourceCardInstanceId"));
            Long triggerTargetCardInstanceId = asLong(trigger.get("triggerTargetCardInstanceId"));
            Long giftHolderHolomemId = asLong(trigger.get("giftHolderHolomemId"));
            boolean selectionMatched = !effectiveSelectedCardInstanceIds.isEmpty()
                && toBoolean(trigger.get("selectionRequired"))
                && java.util.Objects.equals(
                    selectionGiftHolderCardInstanceId,
                    asLong(trigger.get("giftHolderCardInstanceId"))
                );
            Map<String, Object> effectiveTrigger = trigger;
            if (selectionMatched) {
                effectiveTrigger = new LinkedHashMap<>(trigger);
                effectiveTrigger.put("selectedCardInstanceIds", effectiveSelectedCardInstanceIds);
            }
            if (sourceCardInstanceId == null || sourceCardInstanceId <= 0) {
                sourceCardInstanceId = defaultSourceCardInstanceId;
            }
            Map<String, Object> summary = null;
            if (isHbp01124StoredTrigger(effectiveTrigger)) {
                summary = applyHbp01124StoredTriggerEffect(matchId, userId, effectiveTrigger);
            } else if (!selectionMatched) {
                summary = matchGiftTriggerService.applySingleGiftTriggeredEffect(
                    matchId,
                    userId,
                    triggerType,
                    sourceCardInstanceId,
                    triggerTargetCardInstanceId,
                    turnNumber,
                    giftHolderHolomemId
                );
            }
            if ((summary == null || summary.isEmpty()) && asLong(effectiveTrigger.get("giftHolderCardInstanceId")) != null) {
                summary = matchGiftTriggerService.applyStoredGiftTriggeredEffect(
                    matchId,
                    userId,
                    triggerType,
                    sourceCardInstanceId,
                    triggerTargetCardInstanceId,
                    effectiveTrigger
                );
            }
            if (summary == null || summary.isEmpty()) {
                continue;
            }
            triggeredGifts.add(summary);
            Object executedEffects = summary.get("executedEffects");
            if (executedEffects instanceof List<?> effects) {
                for (Object effect : effects) {
                    if (effect instanceof Map<?, ?> effectMap) {
                        aggregatedExecutedEffects.add(castToMap(effectMap));
                    }
                }
            }
            Object skippedEffects = summary.get("skippedEffects");
            if (skippedEffects instanceof List<?> effects) {
                for (Object effect : effects) {
                    if (effect instanceof Map<?, ?> effectMap) {
                        aggregatedSkippedEffects.add(castToMap(effectMap));
                    }
                }
            }
            Object unsupportedEffects = summary.get("unsupportedEffects");
            if (unsupportedEffects instanceof List<?> effectTypes) {
                for (Object effectType : effectTypes) {
                    String normalizedEffectType = normalize(effectType);
                    if (StringUtils.hasText(normalizedEffectType) && !aggregatedUnsupportedEffects.contains(normalizedEffectType)) {
                        aggregatedUnsupportedEffects.add(normalizedEffectType);
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceActionType", normalize(sourceActionType));
        result.put("triggeredGifts", triggeredGifts);
        result.put("executedEffects", aggregatedExecutedEffects);
        result.put("skippedEffects", aggregatedSkippedEffects);
        result.put("unsupportedEffects", aggregatedUnsupportedEffects);
        result.put("partiallyResolved", !aggregatedSkippedEffects.isEmpty() || !aggregatedUnsupportedEffects.isEmpty());
        result.put("applied", !triggeredGifts.isEmpty());
        return result;
    }

    private boolean isHbp01124StoredTrigger(Map<String, Object> trigger) {
        if (trigger == null || trigger.isEmpty()) {
            return false;
        }
        return "HBP01-124".equals(asText(trigger.get("giftHolderCardId")))
            && "SELF_DOWNED".equals(normalize(trigger.get("triggerType")));
    }

    private Map<String, Object> applyHbp01124StoredTriggerEffect(
        Long matchId,
        Long userId,
        Map<String, Object> trigger
    ) {
        if (matchId == null || userId == null || trigger == null || trigger.isEmpty()) {
            return Map.of();
        }
        Long holderHolomemId = asLong(trigger.get("giftHolderHolomemId"));
        if (holderHolomemId == null || holderHolomemId <= 0) {
            return Map.of();
        }
        Long targetHolomemId = jdbcTemplate.query(
            """
            SELECT h.id
            FROM match_holomems h
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER', 'COLLAB', 'BACK')
              AND h.id <> ?
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, h.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            holderHolomemId
        );
        List<Long> preferredCheerCardInstanceIds = toLongList(trigger.get("giftHolderAttachedCheerCardInstanceIds"));
        String movedCheerCardId = null;
        Long movedCheerRowId = null;
        if (targetHolomemId != null) {
            for (Long cheerCardInstanceId : preferredCheerCardInstanceIds) {
                if (cheerCardInstanceId == null || cheerCardInstanceId <= 0) {
                    continue;
                }
                Map<String, Object> cheerCard = jdbcTemplate.query(
                    """
                    SELECT id, card_id, zone
                    FROM match_cards
                    WHERE id = ?
                      AND match_id = ?
                      AND owner_user_id = ?
                    LIMIT 1
                    """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("card_id", rs.getString("card_id"));
                        row.put("zone", rs.getString("zone"));
                        return row;
                    },
                    cheerCardInstanceId,
                    matchId,
                    userId
                );
                if (cheerCard == null) {
                    continue;
                }
                String zone = normalize(cheerCard.get("zone"));
                String cheerCardId = asText(cheerCard.get("card_id"));
                if (!StringUtils.hasText(cheerCardId) || (!"ARCHIVE".equals(zone) && !"STAGE".equals(zone))) {
                    continue;
                }
                if ("STAGE".equals(zone)) {
                    jdbcTemplate.update(
                        """
                        DELETE FROM match_holomem_cheers c
                        USING match_holomems h
                        WHERE c.match_holomem_id = h.id
                          AND c.match_card_id = ?
                          AND h.match_id = ?
                          AND h.owner_user_id = ?
                        """,
                        cheerCardInstanceId,
                        matchId,
                        userId
                    );
                } else {
                    int movedToStage = jdbcTemplate.update(
                        """
                        UPDATE match_cards
                        SET zone = 'STAGE',
                            order_index = NULL,
                            is_face_down = FALSE,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                          AND match_id = ?
                          AND owner_user_id = ?
                          AND zone = 'ARCHIVE'
                        """,
                        cheerCardInstanceId,
                        matchId,
                        userId
                    );
                    if (movedToStage != 1) {
                        continue;
                    }
                }
                movedCheerRowId = jdbcTemplate.query(
                    """
                    INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong(1) : null,
                    targetHolomemId,
                    cheerCardInstanceId,
                    cheerCardId
                );
                movedCheerCardId = cheerCardId;
                break;
            }
        }

        Map<String, Object> reattach = new LinkedHashMap<>();
        reattach.put("effectType", "REATTACH");
        reattach.put("moveRequested", 1);
        reattach.put("moveApplied", movedCheerCardId == null ? 0 : 1);
        reattach.put("targetHolomemId", targetHolomemId);
        reattach.put(
            "targetHolomemCardInstanceId",
            targetHolomemId == null
                ? null
                : jdbcTemplate.query(
                    "SELECT match_card_id FROM match_holomems WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    targetHolomemId
                )
        );
        reattach.put("movedCheerCardIds", movedCheerCardId == null ? List.of() : List.of(movedCheerCardId));
        reattach.put("movedCheerRowIds", movedCheerRowId == null ? List.of() : List.of(movedCheerRowId));
        reattach.put("sourceMode", "HOLDER_CHEER");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggerType", asText(trigger.get("triggerType")));
        summary.put("giftHolderHolomemId", holderHolomemId);
        summary.put("giftHolderCardInstanceId", asLong(trigger.get("giftHolderCardInstanceId")));
        summary.put("giftHolderCardId", asText(trigger.get("giftHolderCardId")));
        summary.put("giftHolderZone", normalize(trigger.get("giftHolderZone")));
        summary.put("sourceCardInstanceId", asLong(trigger.get("sourceCardInstanceId")));
        summary.put("triggerTargetCardInstanceId", asLong(trigger.get("triggerTargetCardInstanceId")));
        summary.put("rawText", asText(trigger.get("rawText")));
        summary.put("requestedEffects", List.of("REATTACH"));
        summary.put("executedEffects", List.of(reattach));
        summary.put("unsupportedEffects", List.of());
        summary.put(
            "skippedEffects",
            movedCheerCardId == null
                ? List.of(Map.of("effectType", "REATTACH", "applied", false, "reason", "NO_MOVABLE_HOLDER_CHEER_OR_TARGET"))
                : List.of()
        );
        return summary;
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
