package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchTriggeredGiftDamagePreventionExecutionService {

    private static final Pattern DICE_AT_LEAST_PATTERN = Pattern.compile("(\\d+)\\s*以上の時");
    private static final Pattern DICE_AT_MOST_PATTERN = Pattern.compile("(\\d+)\\s*以下の時");

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final DiceService diceService;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final GiftTurnUsageReader giftTurnUsageReader;
    private final MatchGiftTriggerConditionService giftTriggerConditionService;

    MatchTriggeredGiftDamagePreventionExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        DiceService diceService,
        GiftTriggerMatcher giftTriggerMatcher,
        GiftTurnUsageReader giftTurnUsageReader,
        MatchGiftTriggerConditionService giftTriggerConditionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.diceService = diceService;
        this.giftTriggerMatcher = giftTriggerMatcher;
        this.giftTurnUsageReader = giftTurnUsageReader;
        this.giftTriggerConditionService = giftTriggerConditionService;
    }

    Map<String, Object> resolveTriggeredGiftDamagePrevention(
        Long matchId,
        Long defendingUserId,
        Long attackingUserId,
        Long sourceCardInstanceId,
        Long targetCardInstanceId,
        int turnNumber,
        int incomingDamage
    ) {
        if (
            matchId == null
                || defendingUserId == null
                || attackingUserId == null
                || targetCardInstanceId == null
                || targetCardInstanceId <= 0
                || turnNumber <= 0
                || incomingDamage <= 0
        ) {
            return null;
        }

        Map<String, Object> targetHolomem = jdbcTemplate.query(
            """
            SELECT id, match_card_id, zone, current_level
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("zone", normalize(rs.getString("zone")));
                row.put("current_level", normalizeLevelType(rs.getString("current_level")));
                return row;
            },
            matchId,
            defendingUserId,
            targetCardInstanceId
        );
        if (targetHolomem == null || targetHolomem.isEmpty()) {
            return null;
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
            defendingUserId
        );
        if (holders.isEmpty()) {
            return null;
        }

        for (Map<String, Object> holder : holders) {
            Long holderHolomemId = asLong(holder.get("holomem_id"));
            Long holderCardInstanceId = asLong(holder.get("match_card_id"));
            String holderZone = normalize(asText(holder.get("zone")));
            String giftText = loadGiftEffectText(asText(holder.get("passive_text")));
            if (!StringUtils.hasText(giftText)) {
                continue;
            }
            boolean alwaysPreventOpponentDamage = giftText.contains("相手からダメージを受けない")
                || giftText.contains("相手からアーツダメージを受けない");
            if (!alwaysPreventOpponentDamage && !giftTriggerMatcher.matchesGiftTriggerType(giftText, "DAMAGE_RECEIVED")) {
                continue;
            }
            String normalizedGiftText = effectTextParser.normalizeDigits(giftText);
            if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(giftText, holderZone)) {
                continue;
            }
            if (
                normalizedGiftText.contains("ターンに1回")
                    && giftTurnUsageReader.isGiftAlreadyUsedThisTurn(matchId, defendingUserId, turnNumber, holderHolomemId)
            ) {
                continue;
            }
            if (!giftTriggerConditionService.matchesTurnOwnershipCondition(matchId, defendingUserId, giftText)) {
                continue;
            }
            if (!giftTriggerConditionService.matchesLifeComparisonCondition(matchId, defendingUserId, giftText)) {
                continue;
            }
            if (!giftTriggerConditionService.matchesHandCountCondition(matchId, defendingUserId, giftText)) {
                continue;
            }
            if (!matchesGiftDamageReceivedCollabPresenceCondition(matchId, defendingUserId, attackingUserId, giftText)) {
                continue;
            }
            if (
                !matchesGiftDamageReceivedTargetCondition(
                    giftText,
                    asText(targetHolomem.get("zone")),
                    targetCardInstanceId,
                    holderCardInstanceId
                )
            ) {
                continue;
            }
            if (giftText.contains("相手からダメージを受ける時") && Objects.equals(defendingUserId, attackingUserId)) {
                continue;
            }
            if (giftText.contains("このホロメンが") && !Objects.equals(targetCardInstanceId, holderCardInstanceId)) {
                continue;
            }
            if (normalizedGiftText.contains("1stホロメンから") && !"FIRST".equals(asText(targetHolomem.get("current_level")))) {
                continue;
            }
            if (normalizedGiftText.contains("2ndホロメンから") && !"SECOND".equals(asText(targetHolomem.get("current_level")))) {
                continue;
            }
            if (normalizedGiftText.contains("Debutホロメンから") && !"DEBUT".equals(asText(targetHolomem.get("current_level")))) {
                continue;
            }

            Integer diceRoll = null;
            if (giftText.contains("サイコロ")) {
                diceRoll = diceService.rollD6();
            }
            boolean diceMatched = matchesGiftDamageReceivedDiceCondition(giftText, diceRoll);
            boolean prevented = alwaysPreventOpponentDamage || (diceMatched && giftText.contains("そのダメージを受けない"));

            Map<String, Object> executed = new LinkedHashMap<>();
            executed.put("effectType", "PREVENT_DAMAGE");
            executed.put("applied", prevented);
            executed.put("damageBefore", incomingDamage);
            executed.put("damageAfter", prevented ? 0 : incomingDamage);
            if (diceRoll != null) {
                executed.put("diceRoll", diceRoll);
                executed.put("diceMatched", diceMatched);
            }
            if (!prevented) {
                executed.put("skipped", true);
                executed.put("reason", "條件未成立：骰子結果不符");
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("triggerType", "DAMAGE_RECEIVED");
            summary.put("giftHolderHolomemId", holderHolomemId);
            summary.put("giftHolderCardInstanceId", holderCardInstanceId);
            summary.put("giftHolderCardId", asText(holder.get("card_id")));
            summary.put("giftHolderZone", holderZone);
            summary.put("sourceCardInstanceId", sourceCardInstanceId);
            summary.put("triggerTargetCardInstanceId", targetCardInstanceId);
            summary.put("rawText", giftText);
            summary.put("requestedEffects", List.of("PREVENT_DAMAGE"));
            summary.put("executedEffects", List.of(executed));
            summary.put("unsupportedEffects", List.of());
            summary.put("skippedEffects", prevented ? List.of() : List.of(executed));
            summary.put("incomingDamage", incomingDamage);
            summary.put("damageAfter", prevented ? 0 : incomingDamage);
            summary.put("applied", true);
            summary.put("preventedDamage", prevented);
            if (diceRoll != null) {
                summary.put("diceRoll", diceRoll);
                summary.put("diceMatched", diceMatched);
            }
            return summary;
        }
        return null;
    }

    private boolean matchesGiftDamageReceivedTargetCondition(
        String giftText,
        String targetZone,
        Long targetCardInstanceId,
        Long holderCardInstanceId
    ) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (giftText.contains("このホロメンは相手からダメージを受けない")) {
            return Objects.equals(targetCardInstanceId, holderCardInstanceId);
        }
        String normalizedTargetZone = normalize(targetZone);
        if (giftText.contains("このホロメンがダメージを受ける時")) {
            return Objects.equals(targetCardInstanceId, holderCardInstanceId);
        }
        if (giftText.contains("自分のホロメン全員は相手からアーツダメージを受けない")) {
            return true;
        }
        if (giftText.contains("自分のセンターホロメンがダメージを受ける時")) {
            return "CENTER".equals(normalizedTargetZone);
        }
        if (giftText.contains("自分のコラボホロメンがダメージを受ける時")) {
            return "COLLAB".equals(normalizedTargetZone);
        }
        if (giftText.contains("自分のバックホロメンがダメージを受ける時")) {
            return "BACK".equals(normalizedTargetZone);
        }
        return giftText.contains("自分のホロメンが相手からダメージを受ける時")
            || giftText.contains("自分のホロメンがダメージを受ける時");
    }

    private boolean matchesGiftDamageReceivedCollabPresenceCondition(
        Long matchId,
        Long defendingUserId,
        Long attackingUserId,
        String giftText
    ) {
        if (!StringUtils.hasText(giftText)) {
            return true;
        }
        if (!giftText.contains("自分のコラボホロメンがいて、相手のコラボホロメンがいないなら")) {
            return true;
        }
        int ownCollabCount = countHolomemsInZone(matchId, defendingUserId, "COLLAB");
        int opponentCollabCount = countHolomemsInZone(matchId, attackingUserId, "COLLAB");
        return ownCollabCount > 0 && opponentCollabCount == 0;
    }

    private boolean matchesGiftDamageReceivedDiceCondition(String giftText, Integer diceRoll) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (!giftText.contains("サイコロ")) {
            return true;
        }
        if (diceRoll == null || diceRoll <= 0) {
            return false;
        }
        String text = effectTextParser.normalizeDigits(giftText);
        if (text.contains("奇数の時")) {
            return diceRoll % 2 == 1;
        }
        if (text.contains("偶数の時")) {
            return diceRoll % 2 == 0;
        }
        Matcher atLeastMatcher = DICE_AT_LEAST_PATTERN.matcher(text);
        if (atLeastMatcher.find()) {
            try {
                return diceRoll >= Integer.parseInt(atLeastMatcher.group(1));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        Matcher atMostMatcher = DICE_AT_MOST_PATTERN.matcher(text);
        if (atMostMatcher.find()) {
            try {
                return diceRoll <= Integer.parseInt(atMostMatcher.group(1));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private int countHolomemsInZone(Long matchId, Long userId, String zone) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        return count == null ? 0 : count;
    }

    private String loadGiftEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("ギフト")) {
            return null;
        }
        return normalizeGiftText(passiveText);
    }

    private String normalizeGiftText(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return "";
        }
        String normalized = passiveText
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("{", " ")
            .replace("}", " ")
            .replace("\"", " ")
            .replace(":", " ");
        int idx = normalized.indexOf("ギフト");
        if (idx < 0) {
            return normalized.trim();
        }
        String trimmed = normalized.substring(idx).trim();
        String[] stopTokens = { "ブルームエフェクト", "コラボエフェクト", "エクストラ" };
        int end = trimmed.length();
        for (String token : stopTokens) {
            int tokenIdx = trimmed.indexOf(token, "ギフト".length());
            if (tokenIdx > 0 && tokenIdx < end) {
                end = tokenIdx;
            }
        }
        return trimmed.substring(0, end).trim();
    }

    private String normalizeLevelType(String levelType) {
        String normalized = normalize(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }

    private String normalize(Object value) {
        return MatchEffectValueHelper.normalize(value);
    }

    private Long asLong(Object value) {
        return MatchEffectValueHelper.asLong(value);
    }

    private String asText(Object value) {
        return MatchEffectValueHelper.asText(value);
    }
}
