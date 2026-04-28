package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AttackDamageService {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern ART_CRITICAL_PATTERN = Pattern.compile("([赤青黄緑紫白])\\s*[+＋]\\s*(\\d+)");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MatchEffectCombatModifierService matchEffectCombatModifierService;

    public AttackDamageService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchEffectCombatModifierService matchEffectCombatModifierService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.matchEffectCombatModifierService = matchEffectCombatModifierService;
    }

    public AttackDamageResult resolveDamage(AttackDamageContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack damage 缺少必要上下文");
        }

        int baseDamage = resolveArtDamage(context.artEffectJsonText());
        int attachedSupportArtBonus = matchEffectCombatModifierService.resolveAttachedSupportArtBonus(
            context.matchId(),
            context.attackerHolomemId()
        );
        int artTextDamageBonus = matchEffectCombatModifierService.resolveArtTextDamageBonus(
            context.matchId(),
            context.attackerUserId(),
            context.turnNumber(),
            context.attackerHolomemId(),
            context.artEffectJsonText()
        );
        int turnArtDamageModifier = resolveTurnArtDamageModifier(
            context.matchId(),
            context.attackerUserId(),
            context.turnNumber(),
            context.attackerHolomemId()
        );

        ArtCritical artCritical = resolveArtCritical(context.artEffectJsonText());
        String criticalColor = artCritical == null ? null : artCritical.color();
        int criticalBonus = 0;
        boolean criticalApplied = false;
        if (artCritical != null && artCritical.bonus() > 0) {
            String targetColor = context.target() == null ? "" : normalize(context.target().mainColor());
            if (artCritical.color().equals(targetColor)) {
                criticalApplied = true;
                criticalBonus = artCritical.bonus();
            }
        }

        int turnIncomingDamageReduction = context.hasOpponentHolomem()
            ? resolveIncomingDamageReduction(context.matchId(), context.opponentUserId(), context.turnNumber())
            : 0;
        int passiveGiftIncomingDamageReduction = context.hasOpponentHolomem() && context.target() != null
            ? matchEffectCombatModifierService.resolvePassiveGiftIncomingDamageReduction(
                context.matchId(),
                context.opponentUserId(),
                context.target().holomemId(),
                normalizeLevel(context.attackerLevel())
            )
            : 0;
        int attachedSupportIncomingDamageReduction = context.hasOpponentHolomem() && context.target() != null
            ? matchEffectCombatModifierService.resolveAttachedSupportIncomingDamageReduction(
                context.matchId(),
                context.target().holomemId(),
                context.target().zone()
            )
            : 0;
        int passiveGiftArtBonus = context.target() != null
            ? matchEffectCombatModifierService.resolvePassiveGiftArtBonus(
                context.matchId(),
                context.attackerUserId(),
                context.attackerHolomemId(),
                context.target().zone()
            )
            : 0;
        int incomingDamageReduction = turnIncomingDamageReduction
            + passiveGiftIncomingDamageReduction
            + attachedSupportIncomingDamageReduction;
        int totalDamage = Math.max(
            baseDamage
                + attachedSupportArtBonus
                + artTextDamageBonus
                + context.holoxRevealArtBonus()
                + passiveGiftArtBonus
                + turnArtDamageModifier
                + criticalBonus
                - incomingDamageReduction,
            0
        );

        return new AttackDamageResult(
            baseDamage,
            attachedSupportArtBonus,
            artTextDamageBonus,
            context.holoxRevealArtBonus(),
            passiveGiftArtBonus,
            turnArtDamageModifier,
            criticalColor,
            criticalBonus,
            criticalApplied,
            turnIncomingDamageReduction,
            passiveGiftIncomingDamageReduction,
            attachedSupportIncomingDamageReduction,
            incomingDamageReduction,
            totalDamage
        );
    }

    int resolveTurnArtDamageModifier(Long matchId, Long userId, int currentTurn, Long attackerHolomemId) {
        if (matchId == null || userId == null || currentTurn <= 0) {
            return 0;
        }
        String attackerHolomemIdText = attackerHolomemId == null ? "" : attackerHolomemId.toString();
        Integer modifier = jdbcTemplate.query(
            """
            SELECT COALESCE(SUM(modifier_value), 0) AS total
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'DAMAGE_MODIFIER'
              AND expires_turn >= ?
              AND COALESCE(payload ->> 'rawText', '') NOT LIKE '%受けるダメージ%'
              AND COALESCE(payload ->> 'rawText', '') NOT LIKE '%ダメージを受ける%'
              AND (
                COALESCE(payload ->> 'targetHolomemId', '') = ''
                OR (payload ->> 'targetHolomemId') = ?
              )
            """,
            rs -> rs.next() ? rs.getInt("total") : 0,
            matchId,
            userId,
            currentTurn,
            attackerHolomemIdText
        );
        return modifier == null ? 0 : modifier;
    }

    int resolveIncomingDamageReduction(Long matchId, Long targetUserId, int currentTurn) {
        if (matchId == null || targetUserId == null || currentTurn <= 0) {
            return 0;
        }
        Integer reduction = jdbcTemplate.query(
            """
            SELECT COALESCE(SUM(ABS(modifier_value)), 0) AS total
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'DAMAGE_MODIFIER'
              AND expires_turn >= ?
              AND (
                COALESCE(payload ->> 'rawText', '') LIKE '%受けるダメージ%'
                OR COALESCE(payload ->> 'rawText', '') LIKE '%ダメージを受ける%'
              )
            """,
            rs -> rs.next() ? rs.getInt("total") : 0,
            matchId,
            targetUserId,
            currentTurn
        );
        return reduction == null ? 0 : reduction;
    }

    int resolveArtDamage(String effectJsonText) {
        if (!StringUtils.hasText(effectJsonText)) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(effectJsonText);
            int parsed = resolveArtDamageFromEffectJson(root);
            if (parsed > 0) {
                return parsed;
            }
        } catch (Exception ignored) {
            // JSON 解析失敗時走文字 fallback。
        }
        return extractFirstNumber(effectJsonText);
    }

    int resolveArtDamageFromEffectJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.has("value") && node.path("value").canConvertToInt()) {
            int value = node.path("value").asInt(0);
            if (value > 0) {
                return value;
            }
        }
        if (node.has("damage") && node.path("damage").canConvertToInt()) {
            int value = node.path("damage").asInt(0);
            if (value > 0) {
                return value;
            }
        }
        if (node.has("baseDamage") && node.path("baseDamage").canConvertToInt()) {
            int value = node.path("baseDamage").asInt(0);
            if (value > 0) {
                return value;
            }
        }
        if (node.has("amount") && node.path("amount").canConvertToInt()) {
            int value = node.path("amount").asInt(0);
            if (value > 0) {
                return value;
            }
        }
        int fallback = extractFirstNumber(node.path("rawHeader").asText(""));
        if (fallback > 0) {
            return fallback;
        }
        fallback = extractFirstNumber(node.path("rawEffect").asText(""));
        if (fallback > 0) {
            return fallback;
        }
        return extractFirstNumber(node.path("rawText").asText(""));
    }

    ArtCritical resolveArtCritical(String effectJsonText) {
        if (!StringUtils.hasText(effectJsonText)) {
            return null;
        }
        String rawHeader = "";
        String rawEffect = "";
        String rawText = "";
        try {
            JsonNode root = objectMapper.readTree(effectJsonText);
            if (root != null && !root.isNull()) {
                rawHeader = root.path("rawHeader").asText("");
                rawEffect = root.path("rawEffect").asText("");
                rawText = root.path("rawText").asText("");
            }
        } catch (Exception ignored) {
            // 解析失敗改走全文 fallback。
        }
        String merged = rawHeader + " " + rawEffect + " " + rawText + " " + effectJsonText;
        Matcher matcher = ART_CRITICAL_PATTERN.matcher(merged);
        if (!matcher.find()) {
            return null;
        }
        String color = mapJapaneseColorToken(matcher.group(1));
        int bonus;
        try {
            bonus = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (!StringUtils.hasText(color) || bonus <= 0) {
            return null;
        }
        return new ArtCritical(color, bonus);
    }

    private String mapJapaneseColorToken(String token) {
        return switch (token) {
            case "赤" -> "RED";
            case "青" -> "BLUE";
            case "黄" -> "YELLOW";
            case "緑" -> "GREEN";
            case "紫" -> "PURPLE";
            case "白" -> "WHITE";
            default -> "";
        };
    }

    private int extractFirstNumber(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String normalizeLevel(String level) {
        String normalized = normalize(level);
        if (
            normalized.equals("DEBUT") ||
            normalized.equals("FIRST") ||
            normalized.equals("SECOND") ||
            normalized.equals("SPOT") ||
            normalized.equals("BUZZ")
        ) {
            return normalized;
        }
        return "DEBUT";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    record ArtCritical(String color, int bonus) {
    }
}
