package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchGiftTriggerConditionService {

    private static final Pattern HAND_COUNT_AT_LEAST_PATTERN = Pattern.compile("自分の手札が(\\d+)枚以上なら");
    private static final Pattern PASSIVE_GIFT_REFERENCED_ART_NAME_PATTERN = Pattern.compile(
        "アーツ[「『]([^」』]+)[」』]"
    );
    private static final Pattern SPECIAL_DAMAGE_PATTERN = Pattern.compile("特殊ダメージ\\s*(\\d+)");
    private static final Pattern SPECIAL_DAMAGE_AT_LEAST_PATTERN = Pattern.compile("(\\d+)\\s*以上の特殊ダメージ");

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final SearchCriteriaParser searchCriteriaParser;

    MatchGiftTriggerConditionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        GiftTriggerMatcher giftTriggerMatcher,
        SearchCriteriaParser searchCriteriaParser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.giftTriggerMatcher = giftTriggerMatcher;
        this.searchCriteriaParser = searchCriteriaParser;
    }

    boolean matchesReferencedArtNameCondition(String giftText, String attackerArtName) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (!giftText.contains("アーツ")) {
            return true;
        }
        if (!giftText.contains("「") && !giftText.contains("『")) {
            return true;
        }
        Matcher matcher = PASSIVE_GIFT_REFERENCED_ART_NAME_PATTERN.matcher(giftText);
        if (!matcher.find()) {
            return true;
        }
        String requiredArtName = matcher.group(1) == null ? "" : matcher.group(1).trim();
        return StringUtils.hasText(requiredArtName)
            && StringUtils.hasText(attackerArtName)
            && attackerArtName.contains(requiredArtName);
    }

    boolean matchesTurnOwnershipCondition(Long matchId, Long userId, String giftText) {
        if (matchId == null || userId == null || !StringUtils.hasText(giftText)) {
            return false;
        }
        if (!giftText.contains("相手のターン") && !giftText.contains("自分のターン")) {
            return true;
        }
        Long currentTurnPlayerId = jdbcTemplate.query(
            """
            SELECT current_turn_player_id
            FROM matches
            WHERE id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("current_turn_player_id") : null,
            matchId
        );
        if (currentTurnPlayerId == null) {
            return false;
        }
        if (giftText.contains("相手のターン") && userId.equals(currentTurnPlayerId)) {
            return false;
        }
        if (giftText.contains("自分のターン") && !userId.equals(currentTurnPlayerId)) {
            return false;
        }
        return true;
    }

    boolean matchesLifeComparisonCondition(Long matchId, Long userId, String giftText) {
        if (matchId == null || userId == null || !StringUtils.hasText(giftText)) {
            return false;
        }
        boolean requireLessOrEqual = giftText.contains("自分のライフが相手以下");
        boolean requireStrictLess = giftText.contains("自分のライフが相手のライフより少ない")
            || giftText.contains("自分のライフが相手より少ない");
        if (!requireLessOrEqual && !requireStrictLess) {
            return true;
        }
        Integer ownLife = jdbcTemplate.query(
            """
            SELECT current_life
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt("current_life") : null,
            matchId,
            userId
        );
        Integer opponentLife = jdbcTemplate.query(
            """
            SELECT current_life
            FROM match_players
            WHERE match_id = ?
              AND user_id <> ?
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt("current_life") : null,
            matchId,
            userId
        );
        if (ownLife == null || opponentLife == null) {
            return false;
        }
        if (requireLessOrEqual) {
            return ownLife <= opponentLife;
        }
        return ownLife < opponentLife;
    }

    boolean matchesHandCountCondition(Long matchId, Long userId, String giftText) {
        if (matchId == null || userId == null || !StringUtils.hasText(giftText)) {
            return false;
        }
        String normalizedText = effectTextParser.normalizeDigits(giftText);
        Matcher atLeastMatcher = HAND_COUNT_AT_LEAST_PATTERN.matcher(normalizedText);
        if (!atLeastMatcher.find()) {
            return true;
        }
        int requiredHandCount = Integer.parseInt(atLeastMatcher.group(1));
        Integer currentHandCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            rs -> rs.next() ? rs.getInt(1) : null,
            matchId,
            userId
        );
        return currentHandCount != null && currentHandCount >= requiredHandCount;
    }

    boolean matchesSpecialDamageThresholdCondition(
        String giftText,
        String sourceCardId,
        String sourceCardName,
        String sourceArtName
    ) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        String normalizedText = effectTextParser.normalizeDigits(giftText);
        int requiredSpecialDamage = effectTextParser.extractByPattern(normalizedText, SPECIAL_DAMAGE_AT_LEAST_PATTERN);
        if (requiredSpecialDamage <= 0) {
            return true;
        }
        if (!StringUtils.hasText(sourceCardId)) {
            return false;
        }
        if (!giftTriggerMatcher.matchesGiftExplicitSourceNameCondition(giftText, sourceCardName)) {
            return false;
        }
        int sourceSpecialDamage = resolveSourceArtSpecialDamageValue(sourceCardId, sourceArtName);
        return sourceSpecialDamage >= requiredSpecialDamage;
    }

    boolean matchesPerformanceEndCondition(
        Long matchId,
        Long userId,
        int turnNumber,
        Long holderHolomemId,
        String giftText
    ) {
        PerformancePhaseSnapshot snapshot = loadPerformancePhaseSnapshot(matchId, userId, turnNumber);
        if (snapshot == null) {
            return false;
        }
        if (giftText.contains("そのパフォーマンスステップに自分のライフが減っていたら")) {
            int currentLife = jdbcTemplate.query(
                """
                SELECT current_life
                FROM match_players
                WHERE match_id = ?
                  AND user_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getInt("current_life") : 0,
                matchId,
                userId
            );
            if (currentLife >= snapshot.currentLife()) {
                return false;
            }
        }
        if (giftText.contains("このホロメンのHPが減っていないなら")) {
            if (holderHolomemId == null || holderHolomemId <= 0) {
                return false;
            }
            Integer startDamage = snapshot.holomemDamage().get(holderHolomemId);
            Integer currentDamage = jdbcTemplate.query(
                """
                SELECT COALESCE(damage_taken, 0)
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getInt(1) : null,
                matchId,
                userId,
                holderHolomemId
            );
            if (startDamage == null || currentDamage == null || !startDamage.equals(currentDamage)) {
                return false;
            }
        }
        return true;
    }

    boolean matchesStageEnterSourceCondition(
        String giftText,
        String sourceLevelType,
        String sourceStageZone,
        String sourceTagsJson
    ) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (giftText.contains("バックホロメン") && !"BACK".equals(sourceStageZone)) {
            return false;
        }
        if (giftText.contains("センターホロメン") && !"CENTER".equals(sourceStageZone)) {
            return false;
        }
        if (!giftTriggerMatcher.matchesGiftStageEnterSourceLevelCondition(giftText, sourceLevelType)) {
            return false;
        }
        String requiredTag = searchCriteriaParser.resolveTagFromKnownTags(giftText);
        return !StringUtils.hasText(requiredTag) || rowTagsContains(sourceTagsJson, requiredTag);
    }

    boolean matchesCollabSourceCondition(
        String giftText,
        String sourceCardName,
        String sourceLevelType,
        String sourceStageZone,
        String sourceTagsJson
    ) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (!"COLLAB".equals(sourceStageZone)) {
            return false;
        }
        if (!giftTriggerMatcher.matchesGiftStageEnterSourceLevelCondition(giftText, sourceLevelType)) {
            return false;
        }
        String requiredTag = searchCriteriaParser.resolveTagFromKnownTags(giftText);
        if (StringUtils.hasText(requiredTag) && !rowTagsContains(sourceTagsJson, requiredTag)) {
            return false;
        }
        return giftTriggerMatcher.matchesGiftDownedSourceNameCondition(giftText, sourceCardName);
    }

    boolean matchesBatonTouchBackSourceCondition(
        String giftText,
        String sourceCardName,
        String sourceLevelType,
        String sourceStageZone,
        String sourceTagsJson
    ) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (!"BACK".equals(sourceStageZone)) {
            return false;
        }
        if (giftText.contains("バックホロメン") && !"BACK".equals(sourceStageZone)) {
            return false;
        }
        if (!giftTriggerMatcher.matchesGiftStageEnterSourceLevelCondition(giftText, sourceLevelType)) {
            return false;
        }
        String requiredTag = searchCriteriaParser.resolveTagFromKnownTags(giftText);
        if (StringUtils.hasText(requiredTag) && !rowTagsContains(sourceTagsJson, requiredTag)) {
            return false;
        }
        return giftTriggerMatcher.matchesGiftExplicitSourceNameCondition(giftText, sourceCardName);
    }

    boolean matchesDownedSourceCondition(
        String giftText,
        String sourceCardName,
        String sourceLevelType,
        String sourceStageZone,
        String sourceTagsJson,
        String triggerType
    ) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (!Set.of("SELF_DOWNED", "ALLY_DOWNED").contains(triggerType)) {
            return true;
        }
        if (giftText.contains("バックホロメン") && !"BACK".equals(sourceStageZone)) {
            return false;
        }
        if (giftText.contains("センターホロメン") && !"CENTER".equals(sourceStageZone)) {
            return false;
        }
        if (giftText.contains("コラボホロメン") && !"COLLAB".equals(sourceStageZone)) {
            return false;
        }
        if (!giftTriggerMatcher.matchesGiftStageEnterSourceLevelCondition(giftText, sourceLevelType)) {
            return false;
        }
        String requiredTag = searchCriteriaParser.resolveTagFromKnownTags(giftText);
        if (StringUtils.hasText(requiredTag) && !rowTagsContains(sourceTagsJson, requiredTag)) {
            return false;
        }
        return giftTriggerMatcher.matchesGiftDownedSourceNameCondition(giftText, sourceCardName);
    }

    private int resolveSourceArtSpecialDamageValue(String sourceCardId, String sourceArtName) {
        if (!StringUtils.hasText(sourceCardId)) {
            return 0;
        }
        java.util.List<Map<String, Object>> arts;
        if (StringUtils.hasText(sourceArtName)) {
            arts = jdbcTemplate.queryForList(
                """
                SELECT effect_json::text AS effect_json_text,
                       description,
                       name
                FROM member_arts
                WHERE member_card_id = ?
                  AND name = ?
                """,
                sourceCardId,
                sourceArtName
            );
            if (arts.isEmpty()) {
                arts = loadAllArtsForMember(sourceCardId);
            }
        } else {
            arts = loadAllArtsForMember(sourceCardId);
        }
        int maxSpecialDamage = 0;
        for (Map<String, Object> art : arts) {
            String merged = effectTextParser.normalizeDigits(
                MatchEffectValueHelper.asText(art.get("effect_json_text")) + " "
                    + MatchEffectValueHelper.asText(art.get("description"))
            );
            int specialDamage = effectTextParser.extractByPattern(merged, SPECIAL_DAMAGE_PATTERN);
            if (specialDamage > maxSpecialDamage) {
                maxSpecialDamage = specialDamage;
            }
        }
        return maxSpecialDamage;
    }

    private java.util.List<Map<String, Object>> loadAllArtsForMember(String sourceCardId) {
        return jdbcTemplate.queryForList(
            """
            SELECT effect_json::text AS effect_json_text,
                   description,
                   name
            FROM member_arts
            WHERE member_card_id = ?
            """,
            sourceCardId
        );
    }

    private PerformancePhaseSnapshot loadPerformancePhaseSnapshot(Long matchId, Long userId, int turnNumber) {
        if (matchId == null || userId == null || turnNumber <= 0) {
            return null;
        }
        String payloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'PERFORMANCE_SNAPSHOT'
              AND expires_turn = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : null,
            matchId,
            userId,
            turnNumber
        );
        JsonNode payloadNode = effectTextParser.parseEffectJson(payloadText);
        if (payloadNode == null || payloadNode.isNull() || !payloadNode.isObject()) {
            return null;
        }
        int currentLife = payloadNode.path("currentLife").asInt(0);
        Map<Long, Integer> holomemDamage = new LinkedHashMap<>();
        JsonNode damageNode = payloadNode.get("holomemDamage");
        if (damageNode != null && damageNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = damageNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                try {
                    holomemDamage.put(Long.parseLong(field.getKey()), field.getValue().asInt(0));
                } catch (NumberFormatException ignored) {
                    // ignore invalid snapshot key
                }
            }
        }
        return new PerformancePhaseSnapshot(currentLife, holomemDamage);
    }

    private boolean rowTagsContains(String tagsJson, String targetTag) {
        if (!StringUtils.hasText(tagsJson) || !StringUtils.hasText(targetTag)) {
            return false;
        }
        JsonNode tagsNode = effectTextParser.parseEffectJson(tagsJson);
        if (tagsNode == null || !tagsNode.isArray()) {
            return false;
        }
        for (JsonNode tagNode : tagsNode) {
            if (tagNode == null || !tagNode.isTextual()) {
                continue;
            }
            if (targetTag.equals(tagNode.asText().trim())) {
                return true;
            }
        }
        return false;
    }

    private record PerformancePhaseSnapshot(
        int currentLife,
        Map<Long, Integer> holomemDamage
    ) {}
}
