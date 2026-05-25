package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchDamageEffectiveHpResolverService {

    private static final Pattern ATTACHED_SUPPORT_HP_PATTERN = Pattern.compile(
        "この(?:マスコット|ツール|ファン)が付いているホロメンのHP\\s*([+＋−-]\\s*\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_HP_PATTERN = Pattern.compile(
        "HP\\s*([+＋−-]\\s*\\d+)"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;

    MatchDamageEffectiveHpResolverService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        EffectTextParser effectTextParser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.effectTextParser = effectTextParser;
    }

    MatchDamageEffectExecutionService.EffectiveHp resolve(
        Long matchId,
        Long ownerUserId,
        Long holomemId,
        String cardId
    ) {
        int baseHp = jdbcTemplate.query(
            "SELECT hp FROM member_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getInt("hp") : 0,
            cardId
        );
        int attachedSupportHpBonus = resolveAttachedSupportStatBonus(matchId, holomemId);
        PassiveGiftHpTargetContext targetContext = loadPassiveGiftHpTargetContext(matchId, ownerUserId, holomemId);
        PassiveGiftHolderContext holderContext = loadPassiveGiftHolderContext(matchId, ownerUserId, holomemId);
        int passiveGiftHpBonus = targetContext == null || holderContext == null
            ? 0
            : resolvePassiveGiftHpBonusFromHolder(holderContext, targetContext);
        int hp = Math.max(baseHp + attachedSupportHpBonus + passiveGiftHpBonus, 0);
        return new MatchDamageEffectExecutionService.EffectiveHp(baseHp, attachedSupportHpBonus, hp);
    }

    int resolvePassiveGiftHpBonus(Long matchId, Long ownerUserId, Long holomemId) {
        if (matchId == null || ownerUserId == null || holomemId == null) {
            return 0;
        }
        PassiveGiftHpTargetContext targetContext = loadPassiveGiftHpTargetContext(matchId, ownerUserId, holomemId);
        if (targetContext == null) {
            return 0;
        }
        PassiveGiftHolderContext holderContext = loadPassiveGiftHolderContext(matchId, ownerUserId, holomemId);
        if (holderContext == null) {
            return 0;
        }
        return resolvePassiveGiftHpBonusFromHolder(holderContext, targetContext);
    }

    private int resolveAttachedSupportStatBonus(Long matchId, Long matchHolomemId) {
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
        int total = 0;
        for (String effectJsonText : effectJsonTexts) {
            String rawText = extractPassiveGiftRawText(effectJsonText);
            Matcher matcher = ATTACHED_SUPPORT_HP_PATTERN.matcher(rawText);
            while (matcher.find()) {
                total += parseSignedNumber(matcher.group(1));
            }
        }
        return total;
    }

    PassiveGiftHpTargetContext loadPassiveGiftHpTargetContext(Long matchId, Long userId, Long holomemId) {
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
                return new PassiveGiftHpTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    parseTagsJson(rs.getString("tags_json_text")),
                    rs.getInt("attached_cheer_count")
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    PassiveGiftHolderContext loadPassiveGiftHolderContext(Long matchId, Long userId, Long holomemId) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   mc.passive_effect_json::text AS passive_effect_json_text
            FROM match_holomems h
            JOIN member_cards mc ON mc.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
              AND mc.passive_effect_json IS NOT NULL
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new PassiveGiftHolderContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    rs.getString("passive_effect_json_text")
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    int resolvePassiveGiftHpBonusFromHolder(
        PassiveGiftHolderContext holderContext,
        PassiveGiftHpTargetContext targetContext
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText)) {
            return 0;
        }
        if (!rawText.contains("このホロメン") || !rawText.contains("エール1枚につき")) {
            return 0;
        }
        if (!holderContext.holomemId().equals(targetContext.holomemId())) {
            return 0;
        }

        Matcher matcher = PASSIVE_GIFT_HP_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return 0;
        }
        int hpBonusPerCheer = parseSignedNumber(matcher.group(1));
        if (hpBonusPerCheer == 0 || targetContext.attachedCheerCount() <= 0) {
            return 0;
        }
        return hpBonusPerCheer * targetContext.attachedCheerCount();
    }

    private String extractPassiveGiftRawText(String effectJsonText) {
        JsonNode node = effectTextParser.parseEffectJson(effectJsonText);
        if (node != null) {
            return effectTextParser.normalizeDigits(
                effectTextParser.extractText(node, "キーワード", "rawText", "rawEffect", "rawHeader")
            );
        }
        return effectTextParser.normalizeDigits(effectJsonText);
    }

    private Set<String> parseTagsJson(String tagsJsonText) {
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

    record PassiveGiftHpTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        Set<String> tags,
        int attachedCheerCount
    ) {}

    record PassiveGiftHolderContext(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {}
}
