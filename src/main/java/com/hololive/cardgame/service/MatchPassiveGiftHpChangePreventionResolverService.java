package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchPassiveGiftHpChangePreventionResolverService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final SearchCriteriaParser searchCriteriaParser;

    MatchPassiveGiftHpChangePreventionResolverService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        EffectTextParser effectTextParser,
        GiftTriggerMatcher giftTriggerMatcher,
        SearchCriteriaParser searchCriteriaParser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.effectTextParser = effectTextParser;
        this.giftTriggerMatcher = giftTriggerMatcher;
        this.searchCriteriaParser = searchCriteriaParser;
    }

    boolean isHpChangeBlockedByOpponentAbility(
        Long matchId,
        Long sourceUserId,
        Long targetOwnerUserId,
        Long targetHolomemId,
        String effectType
    ) {
        if (matchId == null
            || sourceUserId == null
            || targetOwnerUserId == null
            || targetHolomemId == null
            || Objects.equals(sourceUserId, targetOwnerUserId)
            || "ART_DAMAGE".equals(normalize(effectType))) {
            return false;
        }
        MatchTurnContext turnContext = loadMatchTurnContext(matchId);
        if (turnContext == null
            || !"MAIN".equals(turnContext.phase())
            || !Objects.equals(turnContext.currentTurnPlayerId(), sourceUserId)) {
            return false;
        }
        PassiveGiftHpChangePreventionTargetContext targetContext =
            loadPassiveGiftHpChangePreventionTargetContext(matchId, targetOwnerUserId, targetHolomemId);
        if (targetContext == null) {
            return false;
        }
        for (PassiveGiftHolderContext holderContext : loadPassiveGiftHpChangePreventionHolderContexts(
            matchId,
            targetOwnerUserId
        )) {
            if (blocksOpponentAbilityHpChangeFromHolder(holderContext, targetContext)) {
                return true;
            }
        }
        return false;
    }

    MatchTurnContext loadMatchTurnContext(Long matchId) {
        return jdbcTemplate.query(
            """
            SELECT current_phase, current_turn_player_id
            FROM matches
            WHERE id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new MatchTurnContext(
                    effectTextParser.normalizeEffectType(rs.getString("current_phase")),
                    asLong(rs.getObject("current_turn_player_id"))
                );
            },
            matchId
        );
    }

    PassiveGiftHpChangePreventionTargetContext loadPassiveGiftHpChangePreventionTargetContext(
        Long matchId,
        Long userId,
        Long holomemId
    ) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   h.current_level,
                   c.name,
                   COALESCE(c.tags_json, '[]'::jsonb)::text AS tags_json_text
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new PassiveGiftHpChangePreventionTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    rs.getString("name"),
                    parseTagsJson(rs.getString("tags_json_text"))
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    List<PassiveGiftHolderContext> loadPassiveGiftHpChangePreventionHolderContexts(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   mc.passive_effect_json::text AS passive_effect_json_text
            FROM match_holomems h
            JOIN member_cards mc ON mc.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER', 'COLLAB')
              AND mc.passive_effect_json IS NOT NULL
            ORDER BY CASE h.zone
                        WHEN 'CENTER' THEN 1
                        WHEN 'COLLAB' THEN 2
                        ELSE 9
                     END,
                     h.id
            """,
            (rs, rowNum) -> new PassiveGiftHolderContext(
                rs.getLong("id"),
                effectTextParser.normalizeEffectType(rs.getString("zone")),
                rs.getString("passive_effect_json_text")
            ),
            matchId,
            userId
        );
    }

    boolean blocksOpponentAbilityHpChangeFromHolder(
        PassiveGiftHolderContext holderContext,
        PassiveGiftHpChangePreventionTargetContext targetContext
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText) || targetContext == null) {
            return false;
        }
        if (!rawText.contains("相手のメインステップ")
            || !rawText.contains("HP")
            || !rawText.contains("相手の能力")
            || !rawText.contains("減らず")
            || !rawText.contains("変動しない")) {
            return false;
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(rawText, holderContext.stageZone())) {
            return false;
        }
        if (rawText.contains("このホロメンのHP")) {
            return Objects.equals(holderContext.holomemId(), targetContext.holomemId());
        }

        if (!matchesPassiveGiftTargetZoneRestriction(rawText, targetContext.stageZone())) {
            return false;
        }
        SearchCriteria criteria = resolveMemberCriteriaFromRawText(rawText);
        if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(targetContext.levelType())) {
            return false;
        }
        if (StringUtils.hasText(criteria.tag()) && !targetContext.tags().contains(criteria.tag())) {
            return false;
        }
        if (StringUtils.hasText(criteria.nameContains())) {
            String cardName = targetContext.cardName() == null ? "" : targetContext.cardName();
            if (!cardName.contains(criteria.nameContains())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesPassiveGiftTargetZoneRestriction(String rawText, String targetStageZone) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        boolean mentionsCenterHolomem = rawText.contains("センターホロメン");
        boolean mentionsCollabHolomem = rawText.contains("コラボホロメン");
        boolean mentionsBackHolomem = rawText.contains("バックホロメン");
        boolean mentionsCenterPositionDamageTarget = rawText.contains("センターポジションで受けるダメージ");
        boolean mentionsCollabPositionDamageTarget = rawText.contains("コラボポジションで受けるダメージ");
        boolean mentionsBackPositionDamageTarget = rawText.contains("バックポジションで受けるダメージ");
        if (!mentionsCenterHolomem
            && !mentionsCollabHolomem
            && !mentionsBackHolomem
            && !mentionsCenterPositionDamageTarget
            && !mentionsCollabPositionDamageTarget
            && !mentionsBackPositionDamageTarget) {
            return true;
        }
        if ((mentionsCenterHolomem || mentionsCenterPositionDamageTarget) && "CENTER".equals(targetStageZone)) {
            return true;
        }
        if ((mentionsCollabHolomem || mentionsCollabPositionDamageTarget) && "COLLAB".equals(targetStageZone)) {
            return true;
        }
        return (mentionsBackHolomem || mentionsBackPositionDamageTarget) && "BACK".equals(targetStageZone);
    }

    private SearchCriteria resolveMemberCriteriaFromRawText(String rawText) {
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

    private String extractPassiveGiftRawText(String passiveEffectJsonText) {
        JsonNode node = effectTextParser.parseEffectJson(passiveEffectJsonText);
        if (node == null) {
            return effectTextParser.normalizeDigits(passiveEffectJsonText);
        }
        return effectTextParser.normalizeDigits(
            effectTextParser.extractText(node, "キーワード", "rawText", "rawEffect", "rawHeader")
        );
    }

    private Set<String> parseTagsJson(String tagsJsonText) {
        if (!StringUtils.hasText(tagsJsonText)) {
            return Set.of();
        }
        try {
            JsonNode node = objectMapper.readTree(tagsJsonText);
            if (node == null || !node.isArray()) {
                return Set.of();
            }
            Set<String> tags = new LinkedHashSet<>();
            for (JsonNode child : node) {
                if (child != null && child.isTextual() && StringUtils.hasText(child.asText())) {
                    tags.add(child.asText().trim());
                }
            }
            return tags;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    record PassiveGiftHolderContext(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {}

    record PassiveGiftHpChangePreventionTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags
    ) {}

    record MatchTurnContext(
        String phase,
        Long currentTurnPlayerId
    ) {}
}
