package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchEffectCombatModifierService {

    private final JdbcTemplate jdbcTemplate;
    private final MatchEffectService matchEffectService;

    public MatchEffectCombatModifierService(JdbcTemplate jdbcTemplate, MatchEffectService matchEffectService) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchEffectService = matchEffectService;
    }

    public int resolveAttachedSupportHpBonus(Long matchId, Long matchHolomemId) {
        return matchEffectService.resolveAttachedSupportStatBonus(
            matchId,
            matchHolomemId,
            MatchEffectService.ATTACHED_SUPPORT_HP_PATTERN
        );
    }

    public int resolveAttachedSupportArtBonus(Long matchId, Long matchHolomemId) {
        return matchEffectService.resolveAttachedSupportStatBonus(
            matchId,
            matchHolomemId,
            MatchEffectService.ATTACHED_SUPPORT_ARTS_PATTERN
        );
    }

    public int resolveAttachedSupportIncomingDamageReduction(
        Long matchId,
        Long matchHolomemId,
        String targetStageZone
    ) {
        if (matchId == null || matchHolomemId == null) {
            return 0;
        }
        List<String> effectJsonTexts = jdbcTemplate.query(
            """
            SELECT sc.effect_json::text AS effect_json_text
            FROM match_holomem_supports hs
            JOIN support_cards sc ON sc.card_id = hs.support_card_id
            JOIN match_holomems h ON h.id = hs.match_holomem_id
            WHERE hs.match_holomem_id = ?
              AND h.match_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> rs.getString("effect_json_text"),
            matchHolomemId,
            matchId
        );
        if (effectJsonTexts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String effectJsonText : effectJsonTexts) {
            total += matchEffectService.extractAttachedSupportIncomingDamageReduction(effectJsonText, targetStageZone);
        }
        return total;
    }

    public List<Map<String, Object>> previewAttachedSupportConditionalTriggers(
        Long matchId,
        Long ownerUserId,
        Long holderHolomemId,
        String triggerType,
        int turnNumber
    ) {
        if (matchId == null || ownerUserId == null || holderHolomemId == null) {
            return List.of();
        }
        String normalizedTriggerType = normalize(triggerType);
        if (!"SELF_DOWNED".equals(normalizedTriggerType) && !"DAMAGE_RECEIVED".equals(normalizedTriggerType)) {
            return List.of();
        }
        List<Map<String, Object>> supportRows = jdbcTemplate.query(
            """
            SELECT hs.match_card_id AS support_card_instance_id,
                   hs.support_card_id,
                   hs.support_type,
                   c.name,
                   sc.effect_type,
                   sc.effect_json::text AS effect_json_text
            FROM match_holomem_supports hs
            JOIN support_cards sc ON sc.card_id = hs.support_card_id
            JOIN cards c ON c.card_id = hs.support_card_id
            JOIN match_holomems h ON h.id = hs.match_holomem_id
            WHERE hs.match_holomem_id = ?
              AND h.match_id = ?
              AND h.owner_user_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("supportCardInstanceId", rs.getLong("support_card_instance_id"));
                row.put("supportCardId", rs.getString("support_card_id"));
                row.put("supportType", rs.getString("support_type"));
                row.put("name", rs.getString("name"));
                row.put("effectType", rs.getString("effect_type"));
                row.put("effectJsonText", rs.getString("effect_json_text"));
                return row;
            },
            holderHolomemId,
            matchId,
            ownerUserId
        );
        if (supportRows.isEmpty()) {
            return List.of();
        }

        boolean opponentTurn = matchEffectService.isOpponentTurnForUser(matchId, ownerUserId);
        List<Map<String, Object>> previews = new ArrayList<>();
        for (Map<String, Object> supportRow : supportRows) {
            String rawText = matchEffectService.extractAttachedSupportRawText(asText(supportRow.get("effectJsonText")));
            String triggerClause = matchEffectService.extractAttachedSupportConditionalTriggerClause(
                rawText,
                normalizedTriggerType
            );
            if (!StringUtils.hasText(triggerClause)) {
                continue;
            }
            if (triggerClause.contains("相手のターンで") && !opponentTurn) {
                continue;
            }
            List<String> requestedEffects = matchEffectService.inferAttachedSupportConditionalRequestedEffects(
                triggerClause,
                asText(supportRow.get("effectType")),
                normalizedTriggerType
            );
            if (requestedEffects.isEmpty()) {
                continue;
            }

            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("triggerType", normalizedTriggerType);
            preview.put("turnNumber", turnNumber);
            preview.put("giftHolderHolomemId", holderHolomemId);
            preview.put("giftHolderCardInstanceId", asLong(supportRow.get("supportCardInstanceId")));
            preview.put("giftHolderCardId", asText(supportRow.get("supportCardId")));
            preview.put("giftHolderCardType", "SUPPORT");
            preview.put("supportType", asText(supportRow.get("supportType")));
            preview.put("supportName", asText(supportRow.get("name")));
            preview.put("rawText", triggerClause);
            preview.put("requestedEffects", requestedEffects);
            preview.put("executedEffects", List.of());
            preview.put("unsupportedEffects", List.of());
            preview.put("skippedEffects", List.of());
            preview.put("selectionRequired", matchEffectService.hasAttachedSupportOptionalOrCostText(triggerClause));
            preview.put("sourceMode", "ATTACHED_SUPPORT_CONDITIONAL_TRIGGER");
            previews.add(preview);
        }
        return previews;
    }

    public int resolvePassiveGiftArtBonus(Long matchId, Long userId, Long attackerHolomemId, String targetZone) {
        if (matchId == null || userId == null || attackerHolomemId == null) {
            return 0;
        }
        MatchEffectService.StaticArtBonusTargetContext attackerContext =
            matchEffectService.loadStaticArtBonusTargetContext(matchId, userId, attackerHolomemId);
        if (attackerContext == null) {
            return 0;
        }
        List<MatchEffectService.PassiveGiftHolderContext> holderContexts =
            matchEffectService.loadPassiveGiftArtBonusHolderContexts(matchId, userId);
        if (holderContexts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (MatchEffectService.PassiveGiftHolderContext holderContext : holderContexts) {
            total += matchEffectService.resolvePassiveGiftArtBonusFromHolder(
                matchId,
                userId,
                holderContext,
                attackerContext,
                targetZone
            );
        }
        return total;
    }

    public Map<String, Integer> resolvePassiveGiftArtCheerCostReduction(
        Long matchId,
        Long userId,
        Long attackerHolomemId,
        String attackerArtName
    ) {
        if (matchId == null || userId == null || attackerHolomemId == null) {
            return Map.of();
        }
        MatchEffectService.PassiveGiftArtCostReductionTargetContext attackerContext =
            matchEffectService.loadPassiveGiftArtCostReductionTargetContext(matchId, userId, attackerHolomemId, attackerArtName);
        if (attackerContext == null) {
            return Map.of();
        }
        List<MatchEffectService.PassiveGiftHolderContext> holderContexts =
            matchEffectService.loadPassiveGiftArtBonusHolderContexts(matchId, userId);
        if (holderContexts.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> total = new LinkedHashMap<>();
        for (MatchEffectService.PassiveGiftHolderContext holderContext : holderContexts) {
            Map<String, Integer> reduction = matchEffectService.resolvePassiveGiftArtCostReductionFromHolder(
                matchId,
                userId,
                holderContext,
                attackerContext
            );
            if (reduction.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Integer> entry : reduction.entrySet()) {
                String color = entry.getKey();
                int value = entry.getValue() == null ? 0 : entry.getValue();
                if (!StringUtils.hasText(color) || value <= 0) {
                    continue;
                }
                total.merge(color, value, Integer::sum);
            }
        }
        return total;
    }

    public int resolveArtTextDamageBonus(
        Long matchId,
        Long userId,
        int turnNumber,
        Long attackerHolomemId,
        String artEffectJsonText
    ) {
        if (matchId == null || userId == null || attackerHolomemId == null || !StringUtils.hasText(artEffectJsonText)) {
            return 0;
        }
        MatchEffectService.ArtSelfBonusTargetContext attackerContext =
            matchEffectService.loadArtSelfBonusTargetContext(matchId, userId, attackerHolomemId);
        if (attackerContext == null) {
            return 0;
        }
        return matchEffectService.resolveArtTextDamageBonusFromRawText(
            matchId,
            userId,
            turnNumber,
            matchEffectService.extractAttachedSupportRawText(artEffectJsonText),
            attackerContext
        );
    }

    public int resolvePassiveGiftIncomingDamageReduction(
        Long matchId,
        Long userId,
        Long targetHolomemId,
        String incomingSourceLevelType
    ) {
        if (matchId == null || userId == null || targetHolomemId == null) {
            return 0;
        }
        MatchEffectService.PassiveGiftIncomingDamageReductionTargetContext targetContext =
            matchEffectService.loadPassiveGiftIncomingDamageReductionTargetContext(
                matchId,
                userId,
                targetHolomemId,
                incomingSourceLevelType
            );
        if (targetContext == null) {
            return 0;
        }
        List<MatchEffectService.PassiveGiftHolderContext> holderContexts =
            matchEffectService.loadPassiveGiftHolderContexts(matchId, userId);
        if (holderContexts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (MatchEffectService.PassiveGiftHolderContext holderContext : holderContexts) {
            total += matchEffectService.resolvePassiveGiftIncomingDamageReductionFromHolder(
                matchId,
                userId,
                holderContext,
                targetContext
            );
        }
        return total;
    }

    public int resolvePassiveGiftHpBonus(Long matchId, Long userId, Long targetHolomemId) {
        if (matchId == null || userId == null || targetHolomemId == null) {
            return 0;
        }
        MatchEffectService.PassiveGiftHpTargetContext targetContext =
            matchEffectService.loadPassiveGiftHpTargetContext(matchId, userId, targetHolomemId);
        if (targetContext == null) {
            return 0;
        }
        MatchEffectService.PassiveGiftHolderContext holderContext =
            matchEffectService.loadPassiveGiftHolderContext(matchId, userId, targetHolomemId);
        if (holderContext == null) {
            return 0;
        }
        return matchEffectService.resolvePassiveGiftHpBonusFromHolder(holderContext, targetContext);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
