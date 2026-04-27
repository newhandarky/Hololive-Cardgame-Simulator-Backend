package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BloomLegacyResolutionBridge {

    private static final Pattern JAPANESE_DIGIT_THREE = Pattern.compile("[３三]");

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BloomLegacyResolutionBridge(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public BloomValidationContext loadValidationContext(BloomAction action) {
        if (action == null) {
            throw new IllegalArgumentException("BLOOM action 不可為空");
        }
        MatchEntity match = matchRepository.findByIdForUpdate(action.matchId())
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
        if (!matchPlayerRepository.existsByMatchIdAndUserId(action.matchId(), action.actorUserId())) {
            throw new IllegalArgumentException("你不在此房間中");
        }

        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        MatchPhase currentPhase = parsePhase(match.getCurrentPhase());
        BloomSourceCardSnapshot sourceCard = loadSourceCard(
            action.matchId(),
            action.actorUserId(),
            action.sourceCardInstanceId()
        );
        BloomTargetSnapshot target = loadTarget(
            action.matchId(),
            action.actorUserId(),
            action.targetHolomemCardInstanceId(),
            turnNumber,
            sourceCard
        );

        return new BloomValidationContext(
            match,
            action.actorUserId(),
            turnNumber,
            match.getCurrentTurnPlayerId(),
            currentPhase,
            String.valueOf(match.getStatus()),
            String.valueOf(match.getLobbyStatus()),
            hasDuplicateAction(action),
            hasPendingDecision(action.matchId(), action.actorUserId()) || hasAnyPendingDecision(action.matchId()),
            sourceCard,
            target
        );
    }

    private BloomSourceCardSnapshot loadSourceCard(Long matchId, Long userId, Long cardInstanceId) {
        return jdbcTemplate.query(
            """
            SELECT
                mc.id AS card_instance_id,
                mc.card_id,
                mc.zone,
                c.name,
                m.level_type,
                m.hp
            FROM match_cards mc
            LEFT JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.id = ?
              AND mc.match_id = ?
              AND mc.owner_user_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                String levelType = rs.getString("level_type");
                return new BloomSourceCardSnapshot(
                    rs.getLong("card_instance_id"),
                    rs.getString("card_id"),
                    rs.getString("name"),
                    normalizeLevel(levelType),
                    rs.getObject("hp") == null ? 0 : rs.getInt("hp"),
                    normalize(rs.getString("zone")),
                    StringUtils.hasText(levelType)
                );
            },
            cardInstanceId,
            matchId,
            userId
        );
    }

    private BloomTargetSnapshot loadTarget(
        Long matchId,
        Long userId,
        Long targetHolomemCardInstanceId,
        int turnNumber,
        BloomSourceCardSnapshot sourceCard
    ) {
        BloomTargetSnapshot baseTarget = jdbcTemplate.query(
            """
            SELECT
                h.id AS holomem_id,
                h.zone,
                h.card_id AS top_card_id,
                c.name AS top_card_name,
                m.level_type AS top_level_type,
                h.damage_taken,
                h.entered_turn_number,
                h.last_bloom_turn
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Long holomemId = rs.getLong("holomem_id");
                String zone = normalize(rs.getString("zone"));
                Long extraBloomAllowanceId = findExtraBloomAllowanceId(matchId, userId, turnNumber, holomemId);
                String topLevelType = normalizeLevel(rs.getString("top_level_type"));
                boolean levelOverrideAllowed = sourceCard != null &&
                    !isBloomLevelNextStep(topLevelType, sourceCard.levelType()) &&
                    canIgnoreBloomLevelByPassiveGift(matchId, userId, rs.getString("top_card_id"), sourceCard);
                return new BloomTargetSnapshot(
                    holomemId,
                    targetHolomemCardInstanceId,
                    rs.getString("top_card_id"),
                    rs.getString("top_card_name"),
                    topLevelType,
                    zone,
                    rs.getInt("damage_taken"),
                    rs.getObject("entered_turn_number") == null ? null : rs.getInt("entered_turn_number"),
                    rs.getObject("last_bloom_turn") == null ? null : rs.getInt("last_bloom_turn"),
                    isStageActionLocked(matchId, userId, turnNumber, zone, holomemId),
                    extraBloomAllowanceId,
                    levelOverrideAllowed
                );
            },
            matchId,
            userId,
            targetHolomemCardInstanceId
        );
        return baseTarget;
    }

    private boolean hasDuplicateAction(BloomAction action) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'BLOOM'
              AND payload ->> 'idempotencyKey' = ?
            """,
            Integer.class,
            action.matchId(),
            action.actorUserId(),
            action.requestedTurnNumber(),
            action.idempotencyKey()
        );
        return count != null && count > 0;
    }

    private boolean hasPendingDecision(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
            """,
            Integer.class,
            matchId,
            userId
        );
        return count != null && count > 0;
    }

    private boolean hasAnyPendingDecision(Long matchId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND status = 'PENDING'
            """,
            Integer.class,
            matchId
        );
        return count != null && count > 0;
    }

    private Long findExtraBloomAllowanceId(Long matchId, Long userId, int turnNumber, Long targetHolomemId) {
        if (targetHolomemId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ALLOW_EXTRA_BLOOM'
              AND expires_turn >= ?
              AND (payload ->> 'targetHolomemId') = ?
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            turnNumber,
            targetHolomemId.toString()
        );
    }

    private boolean isStageActionLocked(Long matchId, Long userId, int turnNumber, String zone, Long holomemId) {
        List<String> payloads = jdbcTemplate.query(
            """
            SELECT payload::text AS payload_text
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ACTION_LOCK'
              AND expires_turn >= ?
            ORDER BY id DESC
            """,
            (rs, rowNum) -> rs.getString("payload_text"),
            matchId,
            userId,
            turnNumber
        );
        String normalizedZone = normalize(zone);
        for (String payloadText : payloads) {
            JsonNode payload = parseJson(payloadText);
            if (payload == null || !matchesLockAction(payload) || !matchesLockZone(payload, normalizedZone)) {
                continue;
            }
            if (matchesLockTargetHolomem(payload, holomemId)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLockAction(JsonNode payload) {
        JsonNode actions = payload.get("actions");
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            return true;
        }
        for (JsonNode action : actions) {
            if ("BLOOM".equals(normalize(action.asText()))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLockZone(JsonNode payload, String zone) {
        JsonNode zones = payload.get("zones");
        if (zones == null || !zones.isArray() || zones.isEmpty()) {
            return true;
        }
        for (JsonNode candidate : zones) {
            if (zone.equals(normalize(candidate.asText()))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLockTargetHolomem(JsonNode payload, Long holomemId) {
        JsonNode targetHolomemId = payload.get("targetHolomemId");
        return targetHolomemId == null || holomemId == null || targetHolomemId.asLong() == holomemId;
    }

    private boolean canIgnoreBloomLevelByPassiveGift(
        Long matchId,
        Long userId,
        String topCardId,
        BloomSourceCardSnapshot sourceCard
    ) {
        if (sourceCard == null || !StringUtils.hasText(topCardId)) {
            return false;
        }
        String passiveText = jdbcTemplate.query(
            """
            SELECT passive_effect_json::text
            FROM member_cards
            WHERE card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString(1) : null,
            topCardId
        );
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("Bloomレベルを無視してBloomできる")) {
            return false;
        }
        String normalizedText = normalizeDigits(passiveText).toUpperCase(Locale.ROOT);
        if (!normalizedText.contains("自分のライフが3以下") || !normalizedText.contains("このホロメン")) {
            return false;
        }
        Integer currentLife = jdbcTemplate.query(
            """
            SELECT current_life
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt(1) : null,
            matchId,
            userId
        );
        if (currentLife == null || currentLife > 3) {
            return false;
        }
        if (normalizedText.contains("手札の2ND") && !"SECOND".equals(sourceCard.levelType())) {
            return false;
        }
        Matcher nameMatcher = Pattern.compile("〈([^〉]+)〉").matcher(normalizedText);
        if (nameMatcher.find()) {
            String requiredName = nameMatcher.group(1);
            return StringUtils.hasText(sourceCard.cardName()) &&
                sourceCard.cardName().toUpperCase(Locale.ROOT).contains(requiredName);
        }
        return true;
    }

    private boolean isBloomLevelNextStep(String fromLevelType, String toLevelType) {
        int fromRank = resolveBloomLevelRank(fromLevelType);
        int toRank = resolveBloomLevelRank(toLevelType);
        return fromRank >= 0 && toRank == fromRank + 1;
    }

    private int resolveBloomLevelRank(String levelType) {
        return switch (normalizeLevel(levelType)) {
            case "DEBUT" -> 0;
            case "FIRST" -> 1;
            case "SECOND" -> 2;
            case "BUZZ" -> 3;
            default -> -1;
        };
    }

    private MatchPhase parsePhase(String rawPhase) {
        if (!StringUtils.hasText(rawPhase)) {
            return MatchPhase.RESET;
        }
        try {
            return MatchPhase.valueOf(rawPhase.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MatchPhase.RESET;
        }
    }

    private String normalizeLevel(String levelType) {
        String normalized = normalize(levelType);
        if (
            "DEBUT".equals(normalized) ||
                "FIRST".equals(normalized) ||
                "SECOND".equals(normalized) ||
                "SPOT".equals(normalized) ||
                "BUZZ".equals(normalized)
        ) {
            return normalized;
        }
        return StringUtils.hasText(normalized) ? normalized : "DEBUT";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeDigits(String value) {
        return JAPANESE_DIGIT_THREE.matcher(value == null ? "" : value).replaceAll("3");
    }

    private JsonNode parseJson(String payloadText) {
        if (!StringUtils.hasText(payloadText)) {
            return null;
        }
        try {
            return objectMapper.readTree(payloadText);
        } catch (Exception ignored) {
            return null;
        }
    }
}
