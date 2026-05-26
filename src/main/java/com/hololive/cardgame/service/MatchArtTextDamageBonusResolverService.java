package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchArtTextDamageBonusResolverService {

    private static final Pattern ARTS_MODIFIER_PATTERN = Pattern.compile("アーツ\\s*([+＋\\-−]\\s*\\d+)");
    private static final Pattern PASSIVE_GIFT_REFERENCED_OSHI_SKILL_PATTERN = Pattern.compile(
        "(SP)?推しスキル[「『]([^」』]+)[」』]を使っていた"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;

    MatchArtTextDamageBonusResolverService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        EffectTextParser effectTextParser,
        GiftTriggerMatcher giftTriggerMatcher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.effectTextParser = effectTextParser;
        this.giftTriggerMatcher = giftTriggerMatcher;
    }

    int resolveArtTextDamageBonus(
        Long matchId,
        Long userId,
        int turnNumber,
        Long attackerHolomemId,
        String artEffectJsonText
    ) {
        if (matchId == null || userId == null || attackerHolomemId == null || !StringUtils.hasText(artEffectJsonText)) {
            return 0;
        }
        ArtSelfBonusTargetContext attackerContext =
            loadArtSelfBonusTargetContext(matchId, userId, attackerHolomemId);
        if (attackerContext == null) {
            return 0;
        }
        return resolveArtTextDamageBonusFromRawText(
            matchId,
            userId,
            turnNumber,
            extractArtRawText(artEffectJsonText),
            attackerContext
        );
    }

    ArtSelfBonusTargetContext loadArtSelfBonusTargetContext(Long matchId, Long userId, Long holomemId) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   h.current_level,
                   mp.current_life,
                   oshi.name AS oshi_card_name,
                   COALESCE(c.tags_json, '[]'::jsonb)::text AS tags_json_text,
                   (
                       SELECT COUNT(*)
                       FROM match_holomem_cheers hc
                       WHERE hc.match_holomem_id = h.id
                   ) AS attached_cheer_count
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            JOIN match_players mp
              ON mp.match_id = h.match_id
             AND mp.user_id = h.owner_user_id
            LEFT JOIN cards oshi ON oshi.card_id = mp.oshi_card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new ArtSelfBonusTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    parseTagsJson(rs.getString("tags_json_text")),
                    rs.getInt("attached_cheer_count"),
                    rs.getInt("current_life"),
                    rs.getString("oshi_card_name")
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    int resolveArtTextDamageBonusFromRawText(
        Long matchId,
        Long userId,
        int turnNumber,
        String rawText,
        ArtSelfBonusTargetContext attackerContext
    ) {
        if (!StringUtils.hasText(rawText) || attackerContext == null) {
            return 0;
        }
        int total = 0;

        String cheerClause = extractSentenceFromMarker(rawText, "このホロメンのエール1枚につき");
        if (StringUtils.hasText(cheerClause) && cheerClause.contains("このアーツ")) {
            int artBonusPerCheer = extractArtsModifierTotal(cheerClause);
            if (artBonusPerCheer != 0 && attackerContext.attachedCheerCount() > 0) {
                total += artBonusPerCheer * attackerContext.attachedCheerCount();
            }
        }

        String lowLifeClause = extractSentenceFromMarker(rawText, "自分のライフが3以下の時");
        if (StringUtils.hasText(lowLifeClause) && lowLifeClause.contains("このアーツ") && attackerContext.currentLife() <= 3) {
            total += extractArtsModifierTotal(lowLifeClause);
        }

        String ownHolomemArtClause = extractSentenceFromMarker(rawText, "このターンに自分の〈");
        if (StringUtils.hasText(ownHolomemArtClause)
            && ownHolomemArtClause.contains("〉がアーツを使っていたなら")
            && ownHolomemArtClause.contains("このアーツ")) {
            List<String> requiredNames = giftTriggerMatcher.extractNameTokens(ownHolomemArtClause);
            if (didUserUseArtWithNamedHolomemThisTurn(matchId, userId, turnNumber, requiredNames)) {
                total += extractArtsModifierTotal(ownHolomemArtClause);
            }
        }

        String ownOshiSkillClause = extractSentenceFromMarker(rawText, "このターンに自分の推しスキル");
        if (StringUtils.hasText(ownOshiSkillClause)
            && ownOshiSkillClause.contains("使っていたなら")
            && ownOshiSkillClause.contains("このアーツ")) {
            String skillName = extractReferencedOshiSkillName(ownOshiSkillClause);
            if (didUserUseOshiSkillThisTurn(matchId, userId, turnNumber, skillName)) {
                total += extractArtsModifierTotal(ownOshiSkillClause);
            }
        }

        String attachedCheerThresholdClause = extractSentenceFromMarker(rawText, "このホロメンにエールが");
        if (StringUtils.hasText(attachedCheerThresholdClause)
            && attachedCheerThresholdClause.contains("枚以上付いているなら")
            && attachedCheerThresholdClause.contains("このアーツ")) {
            String requiredOshiName = resolveRequiredOshiName(rawText);
            Integer minimumAttachedCheerCount = extractMinimumAttachedCheerCount(attachedCheerThresholdClause);
            if (matchesRequiredOshiName(requiredOshiName, attackerContext.oshiCardName())
                && minimumAttachedCheerCount != null
                && attackerContext.attachedCheerCount() >= minimumAttachedCheerCount) {
                total += extractArtsModifierTotal(attachedCheerThresholdClause);
            }
        }

        return total;
    }

    private Integer extractMinimumAttachedCheerCount(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        Matcher matcher = Pattern.compile("このホロメンにエールが([0-9０-９]+)枚以上付いている").matcher(rawText);
        if (!matcher.find()) {
            return null;
        }
        return parseSignedNumber(matcher.group(1));
    }

    private boolean matchesRequiredOshiName(String requiredOshiName, String actualOshiCardName) {
        if (!StringUtils.hasText(requiredOshiName)) {
            return true;
        }
        return StringUtils.hasText(actualOshiCardName) && actualOshiCardName.contains(requiredOshiName);
    }

    private boolean didUserUseArtWithNamedHolomemThisTurn(
        Long matchId,
        Long userId,
        int turnNumber,
        List<String> requiredNames
    ) {
        if (matchId == null || userId == null || turnNumber <= 0 || requiredNames == null || requiredNames.isEmpty()) {
            return false;
        }
        List<String> attackerNames = jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_actions ma
            JOIN cards c ON c.card_id = ma.payload ->> 'attackerCardId'
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.turn_number = ?
              AND ma.action_type = 'ATTACK_ART'
            ORDER BY ma.id
            """,
            (rs, rowNum) -> rs.getString("name"),
            matchId,
            userId,
            turnNumber
        );
        if (attackerNames.isEmpty()) {
            return false;
        }
        for (String attackerName : attackerNames) {
            if (containsAnyName(attackerName, requiredNames)) {
                return true;
            }
        }
        return false;
    }

    private boolean didUserUseOshiSkillThisTurn(Long matchId, Long userId, int turnNumber, String skillName) {
        if (matchId == null || userId == null || turnNumber <= 0 || !StringUtils.hasText(skillName)) {
            return false;
        }
        Integer count = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'USE_OSHI_SKILL'
              AND payload ->> 'skillName' = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            turnNumber,
            skillName
        );
        return count != null && count > 0;
    }

    private String extractReferencedOshiSkillName(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        Matcher matcher = PASSIVE_GIFT_REFERENCED_OSHI_SKILL_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(2) == null ? "" : matcher.group(2).trim();
    }

    private String resolveRequiredOshiName(String rawText) {
        if (!StringUtils.hasText(rawText) || !rawText.contains("推しホロメン")) {
            return null;
        }
        Matcher matcher = Pattern.compile("推しホロメンが〈([^〉]+)〉").matcher(rawText);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractSentenceFromMarker(String rawText, String marker) {
        if (!StringUtils.hasText(rawText) || !StringUtils.hasText(marker)) {
            return null;
        }
        int markerIndex = rawText.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String clause = rawText.substring(markerIndex).trim();
        int sentenceEnd = clause.indexOf('。');
        if (sentenceEnd >= 0) {
            clause = clause.substring(0, sentenceEnd);
        }
        return clause.trim();
    }

    private String extractArtRawText(String effectJsonText) {
        try {
            JsonNode node = objectMapper.readTree(effectJsonText);
            return effectTextParser.normalizeDigits(effectTextParser.extractText(node, "rawText", "rawEffect", "rawHeader"));
        } catch (Exception ignored) {
            return effectTextParser.normalizeDigits(effectJsonText);
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

    private boolean containsAnyName(String source, List<String> candidates) {
        if (!StringUtils.hasText(source) || candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && source.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    record ArtSelfBonusTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        Set<String> tags,
        int attachedCheerCount,
        int currentLife,
        String oshiCardName
    ) {}
}
