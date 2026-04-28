package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AttackTargetService {

    private static final Pattern CENTER_TAG_REQUIREMENT_PATTERN =
        Pattern.compile("#([^\\sを]+)を持つセンターホロメンがいる間");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AttackTargetService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AttackTargetResult resolveTarget(AttackTargetContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack target 缺少必要上下文");
        }
        Integer opponentHolomemCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            """,
            Integer.class,
            context.matchId(),
            context.opponentUserId()
        );
        boolean hasOpponentHolomem = opponentHolomemCount != null && opponentHolomemCount > 0;
        if (!hasOpponentHolomem) {
            return AttackTargetResult.noOpponent(context.requestedTargetCardInstanceId());
        }

        AttackTargetHolomem target = resolveOpponentTargetHolomem(
            context.matchId(),
            context.opponentUserId(),
            context.requestedTargetCardInstanceId()
        );
        if (target == null) {
            throw new IllegalStateException("DAMAGE 找不到可攻擊的對手 Holomen");
        }

        boolean passiveGiftTargetRestrictionToCollab = hasPassiveGiftTargetRestrictionToCollab(
            context.matchId(),
            context.opponentUserId()
        );
        boolean passiveGiftTargetRestrictionApplied = false;
        if (passiveGiftTargetRestrictionToCollab) {
            if (!"COLLAB".equals(target.zone())) {
                if (context.requestedTargetCardInstanceId() != null && context.requestedTargetCardInstanceId() > 0) {
                    throw new IllegalStateException("對手有用心棒效果，藝能只能以對手 COLLAB Holomen 為目標");
                }
                AttackTargetHolomem collabTarget = loadOpponentCollabTargetHolomem(
                    context.matchId(),
                    context.opponentUserId()
                );
                if (collabTarget == null) {
                    throw new IllegalStateException("對手有用心棒效果，目前沒有可被指定的 COLLAB Holomen");
                }
                target = collabTarget;
            }
            passiveGiftTargetRestrictionApplied = true;
        }

        AttackTargetHolomem targetBeforeRedirect = target;
        Long damageRedirectEffectId = null;
        if (context.resolveDamageRedirect()) {
            DamageRedirectTarget redirectTarget = resolveDamageRedirectTarget(
                context.matchId(),
                context.opponentUserId(),
                context.turnNumber()
            );
            if (redirectTarget != null) {
                target = redirectTarget.target();
                damageRedirectEffectId = redirectTarget.effectId();
            }
        }
        Long effectiveTargetCardInstanceId = target == null
            ? context.requestedTargetCardInstanceId()
            : target.matchCardInstanceId();
        boolean damageRedirectApplied = context.requestedTargetCardInstanceId() != null
            && context.requestedTargetCardInstanceId() > 0
            && !context.requestedTargetCardInstanceId().equals(effectiveTargetCardInstanceId);

        return new AttackTargetResult(
            true,
            target,
            targetBeforeRedirect,
            effectiveTargetCardInstanceId,
            passiveGiftTargetRestrictionToCollab,
            passiveGiftTargetRestrictionApplied,
            damageRedirectApplied,
            damageRedirectEffectId
        );
    }

    AttackTargetHolomem resolveOpponentTargetHolomem(
        Long matchId,
        Long opponentUserId,
        Long requestedTargetCardInstanceId
    ) {
        if (opponentUserId == null) {
            return null;
        }
        if (requestedTargetCardInstanceId != null && requestedTargetCardInstanceId > 0) {
            return jdbcTemplate.query(
                """
                SELECT h.id, h.match_card_id, h.card_id, h.zone, m.main_color
                FROM match_holomems h
                JOIN member_cards m ON m.card_id = h.card_id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                  AND h.match_card_id = ?
                LIMIT 1
                """,
                rs -> rs.next()
                    ? new AttackTargetHolomem(
                        rs.getLong("id"),
                        rs.getLong("match_card_id"),
                        rs.getString("card_id"),
                        normalize(rs.getString("zone")),
                        normalize(rs.getString("main_color"))
                    )
                    : null,
                matchId,
                opponentUserId,
                requestedTargetCardInstanceId
            );
        }
        return jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, h.card_id, h.zone, m.main_color
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, h.id
            LIMIT 1
            """,
            rs -> rs.next()
                ? new AttackTargetHolomem(
                    rs.getLong("id"),
                    rs.getLong("match_card_id"),
                    rs.getString("card_id"),
                    normalize(rs.getString("zone")),
                    normalize(rs.getString("main_color"))
                )
                : null,
            matchId,
            opponentUserId
        );
    }

    AttackTargetHolomem loadOpponentCollabTargetHolomem(Long matchId, Long opponentUserId) {
        if (opponentUserId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, h.card_id, h.zone, m.main_color
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'COLLAB'
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> rs.next()
                ? new AttackTargetHolomem(
                    rs.getLong("id"),
                    rs.getLong("match_card_id"),
                    rs.getString("card_id"),
                    normalize(rs.getString("zone")),
                    normalize(rs.getString("main_color"))
                )
                : null,
            matchId,
            opponentUserId
        );
    }

    boolean hasPassiveGiftTargetRestrictionToCollab(Long matchId, Long ownerUserId) {
        if (matchId == null || ownerUserId == null) {
            return false;
        }
        List<String> passiveTexts = jdbcTemplate.query(
            """
            SELECT mc.passive_effect_json::text AS passive_text
            FROM match_holomems h
            JOIN member_cards mc ON mc.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'COLLAB'
              AND mc.passive_effect_json IS NOT NULL
              AND mc.passive_effect_json::text LIKE '%相手のホロメンのアーツは%'
              AND mc.passive_effect_json::text LIKE '%自分のコラボホロメンしか対象にできない%'
            """,
            (rs, rowNum) -> rs.getString("passive_text"),
            matchId,
            ownerUserId
        );
        for (String passiveText : passiveTexts) {
            if (!StringUtils.hasText(passiveText)) {
                continue;
            }
            String requiredCenterTag = extractRequiredCenterTagForPassiveTargetRestriction(passiveText);
            if (StringUtils.hasText(requiredCenterTag)
                && !hasCenterHolomemWithTag(matchId, ownerUserId, requiredCenterTag)) {
                continue;
            }
            return true;
        }
        return false;
    }

    String extractRequiredCenterTagForPassiveTargetRestriction(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return "";
        }
        Matcher matcher = CENTER_TAG_REQUIREMENT_PATTERN.matcher(passiveText);
        if (!matcher.find()) {
            return "";
        }
        String tagToken = matcher.group(1);
        if (!StringUtils.hasText(tagToken)) {
            return "";
        }
        return "#" + tagToken.trim();
    }

    boolean hasCenterHolomemWithTag(Long matchId, Long ownerUserId, String requiredTag) {
        if (matchId == null || ownerUserId == null || !StringUtils.hasText(requiredTag)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'CENTER'
              AND jsonb_exists(COALESCE(c.tags_json, '[]'::jsonb), ?)
            """,
            Integer.class,
            matchId,
            ownerUserId,
            requiredTag
        );
        return count != null && count > 0;
    }

    DamageRedirectTarget resolveDamageRedirectTarget(Long matchId, Long affectedUserId, int currentTurn) {
        if (matchId == null || affectedUserId == null || currentTurn <= 0) {
            return null;
        }
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
            """
            SELECT id, payload::text AS payload_text
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ACTION_LOCK'
              AND expires_turn >= ?
            ORDER BY id DESC
            """,
            matchId,
            affectedUserId,
            currentTurn
        );
        for (Map<String, Object> row : candidates) {
            Long effectId = asLong(row.get("id"));
            JsonNode payload = parseJson(asString(row.get("payload_text")));
            if (!matchesLockAction(payload, "DAMAGE_REDIRECT")) {
                continue;
            }
            Long targetHolomemId = extractJsonLong(payload, "targetHolomemId");
            if (targetHolomemId == null || targetHolomemId <= 0) {
                continue;
            }
            AttackTargetHolomem redirectTarget = loadTargetHolomemById(matchId, affectedUserId, targetHolomemId);
            if (redirectTarget == null) {
                continue;
            }
            if (effectId != null && effectId > 0) {
                jdbcTemplate.update(
                    "DELETE FROM match_turn_effects WHERE id = ? AND match_id = ?",
                    effectId,
                    matchId
                );
            }
            return new DamageRedirectTarget(effectId, redirectTarget);
        }
        return null;
    }

    AttackTargetHolomem loadTargetHolomemById(Long matchId, Long ownerUserId, Long holomemId) {
        if (matchId == null || ownerUserId == null || holomemId == null || holomemId <= 0) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, h.card_id, h.zone, m.main_color
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> rs.next()
                ? new AttackTargetHolomem(
                    rs.getLong("id"),
                    rs.getLong("match_card_id"),
                    rs.getString("card_id"),
                    normalize(rs.getString("zone")),
                    normalize(rs.getString("main_color"))
                )
                : null,
            matchId,
            ownerUserId,
            holomemId
        );
    }

    private boolean matchesLockAction(JsonNode payload, String actionKey) {
        JsonNode actions = payload.get("actions");
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            return true;
        }
        for (JsonNode actionNode : actions) {
            if (normalize(actionNode.asText()).equals(actionKey)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.nullNode();
        }
    }

    private Long extractJsonLong(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record DamageRedirectTarget(Long effectId, AttackTargetHolomem target) {}
}
