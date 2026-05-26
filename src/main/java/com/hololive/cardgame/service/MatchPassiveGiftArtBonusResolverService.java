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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchPassiveGiftArtBonusResolverService {

    private static final Pattern ARTS_MODIFIER_PATTERN = Pattern.compile("アーツ\\s*([+＋\\-−]\\s*\\d+)");
    private static final Pattern PASSIVE_GIFT_SPECIAL_DAMAGE_BONUS_PATTERN = Pattern.compile("特殊ダメージ\\s*[+＋]\\s*(\\d+)");
    private static final Pattern OPPONENT_STAGE_TAG_PRESENCE_PATTERN = Pattern.compile(
        "相手のステージに\\[([^\\]]+)]を持つホロメンがいる"
    );
    private static final Pattern INLINE_TAG_TOKEN_PATTERN = Pattern.compile(
        "#([\\p{L}\\p{N}_'\\-]+?)(?=(?:#|か|を|が|に|で|と|へ|や|も|、|。|\\]|\\s|$))"
    );
    private static final Pattern PASSIVE_GIFT_REFERENCED_OSHI_SKILL_PATTERN = Pattern.compile(
        "(SP)?推しスキル[「『]([^」』]+)[」』]を使っていた"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final SearchCriteriaParser searchCriteriaParser;

    MatchPassiveGiftArtBonusResolverService(
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

    int resolvePassiveGiftArtBonus(Long matchId, Long userId, Long attackerHolomemId, String targetZone) {
        if (matchId == null || userId == null || attackerHolomemId == null) {
            return 0;
        }
        StaticArtBonusTargetContext attackerContext =
            loadStaticArtBonusTargetContext(matchId, userId, attackerHolomemId);
        if (attackerContext == null) {
            return 0;
        }
        List<PassiveGiftHolderContext> holderContexts = loadPassiveGiftArtBonusHolderContexts(matchId, userId);
        if (holderContexts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (PassiveGiftHolderContext holderContext : holderContexts) {
            total += resolvePassiveGiftArtBonusFromHolder(
                matchId,
                userId,
                holderContext,
                attackerContext,
                targetZone
            );
        }
        return total;
    }

    StaticArtBonusTargetContext loadStaticArtBonusTargetContext(Long matchId, Long userId, Long holomemId) {
        Set<String> opponentStageTags = loadOpponentStageTags(matchId, userId);
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
                return new StaticArtBonusTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    rs.getString("name"),
                    parseTagsJson(rs.getString("tags_json_text")),
                    opponentStageTags
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

    int resolvePassiveGiftArtBonusFromHolder(
        Long matchId,
        Long userId,
        PassiveGiftHolderContext holderContext,
        StaticArtBonusTargetContext attackerContext,
        String targetZone
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText) || attackerContext == null) {
            return 0;
        }
        int artBonus = extractArtsModifierTotal(rawText)
            + extractPassiveGiftSpecialDamageBonus(rawText, attackerContext, targetZone);
        if (artBonus > 0 && rawText.contains("2ndホロメンがいるなら")) {
            int conditionalExtraBonus = extractArtsModifierTotal(extractClauseAfter(rawText, "さらに"));
            if (conditionalExtraBonus > 0 && !hasStageHolomemWithLevelType(matchId, userId, "SECOND")) {
                artBonus = Math.max(artBonus - conditionalExtraBonus, 0);
            }
        }
        if (artBonus == 0) {
            return 0;
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(rawText, holderContext.stageZone())) {
            return 0;
        }
        if (!matchesPassiveGiftAttachedSupportCondition(rawText, holderContext.holomemId())) {
            return 0;
        }
        if (!matchesPassiveGiftArtTargetZoneRestriction(rawText, attackerContext.stageZone())) {
            return 0;
        }
        if (rawText.contains("このホロメンのアーツ")
            && !Objects.equals(holderContext.holomemId(), attackerContext.holomemId())) {
            return 0;
        }
        if (!matchesPassiveGiftOpponentStageTagCondition(rawText, attackerContext.opponentStageTags())) {
            return 0;
        }
        if (!matchesPassiveGiftHistoricalOshiSkillCondition(matchId, userId, rawText)) {
            return 0;
        }
        if (rawText.contains("このホロメンのアーツ")) {
            return artBonus;
        }

        String targetClause = extractPassiveGiftArtBonusTargetClause(rawText);
        if (!matchesPassiveGiftTargetAttachedSupportCondition(targetClause, attackerContext.holomemId())) {
            return 0;
        }
        String normalizedTargetClause = stripPassiveGiftTargetAttachedSupportCondition(targetClause);
        if (normalizedTargetClause.contains("このホロメン以外")
            && Objects.equals(holderContext.holomemId(), attackerContext.holomemId())) {
            return 0;
        }
        SearchCriteria criteria = resolveMemberCriteriaFromRawText(normalizedTargetClause);
        if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(attackerContext.levelType())) {
            return 0;
        }
        if (StringUtils.hasText(criteria.tag()) && !attackerContext.tags().contains(criteria.tag())) {
            return 0;
        }
        if (!matchesPassiveGiftArtTargetNameCondition(normalizedTargetClause, attackerContext.cardName())) {
            return 0;
        }
        return artBonus;
    }

    private int extractPassiveGiftSpecialDamageBonus(
        String rawText,
        StaticArtBonusTargetContext attackerContext,
        String targetZone
    ) {
        if (!StringUtils.hasText(rawText) || attackerContext == null) {
            return 0;
        }
        int specialDamageBonus = effectTextParser.extractByPattern(rawText, PASSIVE_GIFT_SPECIAL_DAMAGE_BONUS_PATTERN);
        if (specialDamageBonus <= 0) {
            return 0;
        }
        if (rawText.contains("相手のセンターホロメンに与える")
            && !"CENTER".equals(effectTextParser.normalizeEffectType(targetZone))) {
            return 0;
        }
        String targetClause = extractTrailingClauseBeforeMarker(rawText, "に与える特殊ダメージ");
        if (StringUtils.hasText(targetClause)
            && !matchesPassiveGiftArtTargetNameCondition(targetClause, attackerContext.cardName())) {
            return 0;
        }
        return specialDamageBonus;
    }

    private boolean matchesPassiveGiftAttachedSupportCondition(String rawText, Long holderHolomemId) {
        if (!StringUtils.hasText(rawText) || holderHolomemId == null) {
            return true;
        }
        int attachedIndex = rawText.indexOf("が付いている");
        if (!rawText.contains("が付いている間") && attachedIndex < 0) {
            return true;
        }
        String requirementPrefix = attachedIndex < 0 ? rawText : rawText.substring(0, attachedIndex);
        List<String> requiredSupportNames = giftTriggerMatcher.extractNameTokens(requirementPrefix);
        if (requiredSupportNames.isEmpty()) {
            return true;
        }
        List<String> attachedSupportNames = loadAttachedSupportNames(holderHolomemId);
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

    private boolean matchesPassiveGiftOpponentStageTagCondition(String rawText, Set<String> opponentStageTags) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        Matcher matcher = OPPONENT_STAGE_TAG_PRESENCE_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return true;
        }
        Set<String> requiredTags = parseTagsFromText(matcher.group(1));
        if (requiredTags.isEmpty()) {
            return false;
        }
        Set<String> actualTags = opponentStageTags == null ? Set.of() : opponentStageTags;
        for (String requiredTag : requiredTags) {
            if (actualTags.contains(requiredTag)) {
                return true;
            }
        }
        return false;
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

    private boolean hasStageHolomemWithLevelType(Long matchId, Long userId, String levelType) {
        if (matchId == null || userId == null || !StringUtils.hasText(levelType)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
              AND UPPER(COALESCE(current_level, '')) = UPPER(?)
            """,
            Integer.class,
            matchId,
            userId,
            levelType
        );
        return count != null && count > 0;
    }

    private Set<String> loadOpponentStageTags(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.query(
            """
            SELECT DISTINCT tag.value AS tag
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(c.tags_json, '[]'::jsonb)) AS tag(value)
            WHERE h.match_id = ?
              AND h.owner_user_id <> ?
              AND h.zone IN ('CENTER', 'COLLAB', 'BACK')
            """,
            (rs, rowNum) -> rs.getString("tag"),
            matchId,
            userId
        ));
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

    private int extractArtsModifierTotal(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return 0;
        }
        Matcher matcher = ARTS_MODIFIER_PATTERN.matcher(rawText);
        int total = 0;
        while (matcher.find()) {
            total += parseSignedNumber(matcher.group(1));
        }
        return total;
    }

    private String extractClauseAfter(String rawText, String marker) {
        if (!StringUtils.hasText(rawText) || !StringUtils.hasText(marker)) {
            return "";
        }
        int index = rawText.indexOf(marker);
        if (index < 0) {
            return "";
        }
        return rawText.substring(index + marker.length());
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

    private boolean matchesPassiveGiftArtTargetNameCondition(String targetClause, String attackerCardName) {
        if (!StringUtils.hasText(targetClause)) {
            return true;
        }
        String normalizedCardName = nullToEmpty(attackerCardName);
        List<String> explicitNameTokens = giftTriggerMatcher.extractNameTokens(targetClause);
        if (!explicitNameTokens.isEmpty()) {
            for (String explicitNameToken : explicitNameTokens) {
                if (StringUtils.hasText(explicitNameToken) && normalizedCardName.contains(explicitNameToken)) {
                    return true;
                }
            }
            return false;
        }
        String nameContains = resolveMemberCriteriaFromRawText(targetClause).nameContains();
        return !StringUtils.hasText(nameContains) || normalizedCardName.contains(nameContains);
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

    private Set<String> parseTagsFromText(String text) {
        Set<String> tags = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return tags;
        }
        Matcher matcher = INLINE_TAG_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tags.add("#" + matcher.group(1));
        }
        return tags;
    }

    private int parseSignedNumber(String token) {
        if (!StringUtils.hasText(token)) {
            return 0;
        }
        String normalized = token.replace("＋", "+").replace("−", "-").replaceAll("\\s+", "");
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String normalize(String value) {
        return MatchEffectValueHelper.normalize(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record StaticArtBonusTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags,
        Set<String> opponentStageTags
    ) {}

    record PassiveGiftHolderContext(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {}
}
