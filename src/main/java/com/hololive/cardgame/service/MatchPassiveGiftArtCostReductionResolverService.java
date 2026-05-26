package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchPassiveGiftArtCostReductionResolverService {

    private static final Pattern PASSIVE_GIFT_ART_COST_REDUCTION_PATTERN = Pattern.compile(
        "アーツ(?:[「『][^」』]+[」』])?に必要な\\s*(赤|青|緑|白|紫|黄|無色)\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_REFERENCED_OSHI_SKILL_PATTERN = Pattern.compile(
        "(SP)?推しスキル[「『]([^」』]+)[」』]を使っていた"
    );
    private static final Pattern PASSIVE_GIFT_REFERENCED_ART_NAME_PATTERN = Pattern.compile(
        "アーツ[「『]([^」』]+)[」』]"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final SearchCriteriaParser searchCriteriaParser;

    MatchPassiveGiftArtCostReductionResolverService(
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

    Map<String, Integer> resolvePassiveGiftArtCheerCostReduction(
        Long matchId,
        Long userId,
        Long attackerHolomemId,
        String attackerArtName
    ) {
        if (matchId == null || userId == null || attackerHolomemId == null) {
            return Map.of();
        }
        PassiveGiftArtCostReductionTargetContext attackerContext =
            loadPassiveGiftArtCostReductionTargetContext(matchId, userId, attackerHolomemId, attackerArtName);
        if (attackerContext == null) {
            return Map.of();
        }
        List<PassiveGiftHolderContext> holderContexts = loadPassiveGiftArtBonusHolderContexts(matchId, userId);
        if (holderContexts.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> total = new LinkedHashMap<>();
        for (PassiveGiftHolderContext holderContext : holderContexts) {
            Map<String, Integer> reduction = resolvePassiveGiftArtCostReductionFromHolder(
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

    PassiveGiftArtCostReductionTargetContext loadPassiveGiftArtCostReductionTargetContext(
        Long matchId,
        Long userId,
        Long holomemId,
        String attackerArtName
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
                return new PassiveGiftArtCostReductionTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    rs.getString("name"),
                    attackerArtName,
                    parseTagsJson(rs.getString("tags_json_text"))
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    List<PassiveGiftHolderContext> loadPassiveGiftArtBonusHolderContexts(Long matchId, Long userId) {
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

    Map<String, Integer> resolvePassiveGiftArtCostReductionFromHolder(
        Long matchId,
        Long userId,
        PassiveGiftHolderContext holderContext,
        PassiveGiftArtCostReductionTargetContext attackerContext
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText) || attackerContext == null) {
            return Map.of();
        }
        Matcher reductionMatcher = PASSIVE_GIFT_ART_COST_REDUCTION_PATTERN.matcher(rawText);
        if (!reductionMatcher.find()) {
            return Map.of();
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(rawText, holderContext.stageZone())) {
            return Map.of();
        }
        if (!matchesPassiveGiftArtTargetZoneRestriction(rawText, attackerContext.stageZone())) {
            return Map.of();
        }
        if (!matchesPassiveGiftHistoricalOshiSkillCondition(matchId, userId, rawText)) {
            return Map.of();
        }
        if (!matchesPassiveGiftReferencedArtNameCondition(rawText, attackerContext.artName())) {
            return Map.of();
        }
        if (rawText.contains("このホロメンのアーツ")) {
            if (!Objects.equals(holderContext.holomemId(), attackerContext.holomemId())) {
                return Map.of();
            }
            return buildPassiveGiftArtCostReductionResult(reductionMatcher);
        }

        String targetClause = extractPassiveGiftArtCostTargetClause(rawText);
        if (!StringUtils.hasText(targetClause)) {
            return Map.of();
        }
        if (targetClause.contains("このホロメン")
            && !Objects.equals(holderContext.holomemId(), attackerContext.holomemId())) {
            return Map.of();
        }
        if (!matchesPassiveGiftTargetAttachedSupportCondition(targetClause, attackerContext.holomemId())) {
            return Map.of();
        }

        String normalizedTargetClause = stripPassiveGiftTargetAttachedSupportCondition(targetClause);
        if (!normalizedTargetClause.contains("このホロメン")) {
            SearchCriteria criteria = resolveMemberCriteriaFromRawText(normalizedTargetClause);
            if (!matchesPassiveGiftArtCostTargetCriteria(criteria, attackerContext)) {
                return Map.of();
            }
        }

        return buildPassiveGiftArtCostReductionResult(reductionMatcher);
    }

    private Map<String, Integer> buildPassiveGiftArtCostReductionResult(Matcher reductionMatcher) {
        if (reductionMatcher == null) {
            return Map.of();
        }
        String color = normalizeColorType(resolveCheerColorFilter(reductionMatcher.group(1)));
        int reduction = Integer.parseInt(reductionMatcher.group(2));
        if (!StringUtils.hasText(color) || reduction <= 0) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put(color, reduction);
        return result;
    }

    private boolean matchesPassiveGiftHistoricalOshiSkillCondition(Long matchId, Long userId, String rawText) {
        if (matchId == null || userId == null || !StringUtils.hasText(rawText)) {
            return false;
        }
        Matcher matcher = PASSIVE_GIFT_REFERENCED_OSHI_SKILL_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return true;
        }
        String skillType = StringUtils.hasText(matcher.group(1)) ? "SP" : null;
        String skillName = matcher.group(2) == null ? "" : matcher.group(2).trim();
        if (!StringUtils.hasText(skillName)) {
            return false;
        }
        Integer count = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'USE_OSHI_SKILL'
              AND (? IS NULL OR payload ->> 'skillType' = ?)
              AND payload ->> 'skillName' = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            skillType,
            skillType,
            skillName
        );
        return count != null && count > 0;
    }

    private boolean matchesPassiveGiftReferencedArtNameCondition(String rawText, String attackerArtName) {
        if (!StringUtils.hasText(rawText)) {
            return false;
        }
        Matcher matcher = PASSIVE_GIFT_REFERENCED_ART_NAME_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return true;
        }
        String requiredArtName = matcher.group(1) == null ? "" : matcher.group(1).trim();
        return StringUtils.hasText(requiredArtName)
            && StringUtils.hasText(attackerArtName)
            && attackerArtName.contains(requiredArtName);
    }

    private boolean matchesPassiveGiftTargetAttachedSupportCondition(String targetClause, Long targetHolomemId) {
        if (!StringUtils.hasText(targetClause) || targetHolomemId == null) {
            return true;
        }
        int attachedIndex = targetClause.indexOf("が付いている");
        if (attachedIndex < 0) {
            return true;
        }
        String requirementPrefix = targetClause.substring(0, attachedIndex);
        if (requirementPrefix.contains("マスコット")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("MASCOT");
        }
        if (requirementPrefix.contains("ツール")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("TOOL");
        }
        if (requirementPrefix.contains("ファン")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("FAN");
        }
        List<String> requiredSupportNames = giftTriggerMatcher.extractNameTokens(requirementPrefix);
        if (requiredSupportNames.isEmpty()) {
            return true;
        }
        List<String> attachedSupportNames = loadAttachedSupportNames(targetHolomemId);
        if (attachedSupportNames.isEmpty()) {
            return false;
        }
        for (String attachedSupportName : attachedSupportNames) {
            if (!StringUtils.hasText(attachedSupportName)) {
                continue;
            }
            for (String requiredSupportName : requiredSupportNames) {
                if (attachedSupportName.contains(requiredSupportName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> loadAttachedSupportTypes(Long holomemId) {
        if (holomemId == null) {
            return Set.of();
        }
        List<String> supportTypes = jdbcTemplate.query(
            """
            SELECT hs.support_type
            FROM match_holomem_supports hs
            WHERE hs.match_holomem_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> normalize(rs.getString("support_type")),
            holomemId
        );
        if (supportTypes == null || supportTypes.isEmpty()) {
            return Set.of();
        }
        return supportTypes.stream()
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> loadAttachedSupportNames(Long holomemId) {
        if (holomemId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_holomem_supports hs
            JOIN cards c ON c.card_id = hs.support_card_id
            WHERE hs.match_holomem_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> rs.getString("name"),
            holomemId
        );
    }

    private boolean matchesPassiveGiftArtTargetZoneRestriction(String rawText, String attackerStageZone) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        String targetClause = extractPassiveGiftArtBonusTargetClause(rawText);
        String zoneClause = StringUtils.hasText(targetClause) ? targetClause : rawText;
        boolean mentionsCenterHolomem = zoneClause.contains("センターホロメン");
        boolean mentionsCollabHolomem = zoneClause.contains("コラボホロメン");
        boolean mentionsBackHolomem = zoneClause.contains("バックホロメン");
        if (!mentionsCenterHolomem && !mentionsCollabHolomem && !mentionsBackHolomem) {
            return true;
        }
        if (mentionsCenterHolomem && "CENTER".equals(attackerStageZone)) {
            return true;
        }
        if (mentionsCollabHolomem && "COLLAB".equals(attackerStageZone)) {
            return true;
        }
        return mentionsBackHolomem && "BACK".equals(attackerStageZone);
    }

    private boolean matchesPassiveGiftArtCostTargetCriteria(
        SearchCriteria criteria,
        PassiveGiftArtCostReductionTargetContext attackerContext
    ) {
        if (criteria == null || criteria.isEmpty()) {
            return true;
        }
        if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(attackerContext.levelType())) {
            return false;
        }
        if (StringUtils.hasText(criteria.tag()) && !attackerContext.tags().contains(criteria.tag())) {
            return false;
        }
        return !StringUtils.hasText(criteria.nameContains())
            || nullToEmpty(attackerContext.cardName()).contains(criteria.nameContains());
    }

    private String extractPassiveGiftRawText(String passiveEffectJsonText) {
        try {
            JsonNode node = objectMapper.readTree(passiveEffectJsonText);
            return effectTextParser.normalizeDigits(
                effectTextParser.extractText(node, "キーワード", "rawText", "rawEffect", "rawHeader")
            );
        } catch (Exception ignored) {
            return effectTextParser.normalizeDigits(passiveEffectJsonText);
        }
    }

    private String extractPassiveGiftArtBonusTargetClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int markerIndex = rawText.indexOf("のアーツ");
        if (markerIndex >= 0) {
            return rawText.substring(0, markerIndex).trim();
        }
        String specialDamageClause = extractTrailingClauseBeforeMarker(rawText, "に与える特殊ダメージ");
        if (StringUtils.hasText(specialDamageClause)) {
            int opponentTargetIndex = specialDamageClause.indexOf("が相手の");
            if (opponentTargetIndex > 0) {
                return specialDamageClause.substring(0, opponentTargetIndex).trim();
            }
            return specialDamageClause;
        }
        return rawText.trim();
    }

    private String extractPassiveGiftArtCostTargetClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        String clause = extractTrailingClauseBeforeMarker(rawText, "のアーツに必要な");
        if (StringUtils.hasText(clause)) {
            return clause;
        }
        return extractTrailingClauseBeforeMarker(rawText, "のアーツ");
    }

    private String stripPassiveGiftTargetAttachedSupportCondition(String targetClause) {
        if (!StringUtils.hasText(targetClause)) {
            return "";
        }
        int attachedIndex = targetClause.indexOf("が付いている");
        if (attachedIndex < 0) {
            return targetClause;
        }
        return targetClause.substring(attachedIndex + "が付いている".length()).trim();
    }

    private SearchCriteria resolveMemberCriteriaFromRawText(String rawText) {
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

    private String extractTrailingClauseBeforeMarker(String rawText, String marker) {
        if (!StringUtils.hasText(rawText) || !StringUtils.hasText(marker)) {
            return "";
        }
        int markerIndex = rawText.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        int clauseStart = Math.max(
            Math.max(rawText.lastIndexOf('、', markerIndex), rawText.lastIndexOf('。', markerIndex)),
            rawText.lastIndexOf('\n', markerIndex)
        );
        return rawText.substring(clauseStart < 0 ? 0 : clauseStart + 1, markerIndex).trim();
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

    private String resolveCheerColorFilter(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        if (rawText.contains("赤")) {
            return "RED";
        }
        if (rawText.contains("青")) {
            return "BLUE";
        }
        if (rawText.contains("緑")) {
            return "GREEN";
        }
        if (rawText.contains("白")) {
            return "WHITE";
        }
        if (rawText.contains("紫")) {
            return "PURPLE";
        }
        if (rawText.contains("黄")) {
            return "YELLOW";
        }
        if (rawText.contains("無色")) {
            return "COLORLESS";
        }
        return "";
    }

    private String normalizeColorType(String color) {
        String normalized = normalize(color);
        return switch (normalized) {
            case "RED", "BLUE", "GREEN", "WHITE", "PURPLE", "YELLOW", "COLORLESS" -> normalized;
            default -> "";
        };
    }

    private String normalize(String value) {
        return MatchEffectValueHelper.normalize(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record PassiveGiftHolderContext(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {}

    record PassiveGiftArtCostReductionTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        String artName,
        Set<String> tags
    ) {}
}
