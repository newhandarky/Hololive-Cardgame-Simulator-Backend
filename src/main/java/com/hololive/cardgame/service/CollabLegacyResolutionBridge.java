package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CollabLegacyResolutionBridge {

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CollabLegacyResolutionBridge(
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

    public CollabValidationContext loadValidationContext(CollabAction action) {
        if (action == null) {
            throw new IllegalArgumentException("COLLAB action 不可為空");
        }
        MatchEntity match = matchRepository.findByIdForUpdate(action.matchId())
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
        if (!matchPlayerRepository.existsByMatchIdAndUserId(action.matchId(), action.actorUserId())) {
            throw new IllegalArgumentException("你不在此房間中");
        }

        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        MatchPhase currentPhase = parsePhase(match.getCurrentPhase());
        return new CollabValidationContext(
            match,
            action.actorUserId(),
            turnNumber,
            match.getCurrentTurnPlayerId(),
            currentPhase,
            String.valueOf(match.getStatus()),
            String.valueOf(match.getLobbyStatus()),
            hasDuplicateAction(action),
            hasPendingDecision(action.matchId(), action.actorUserId()) || hasAnyPendingDecision(action.matchId()),
            isStageActionLocked(action.matchId(), action.actorUserId(), turnNumber, "MOVE_STAGE", null, null),
            hasUsedCollabThisTurn(action.matchId(), action.actorUserId(), turnNumber),
            countTargetZoneOccupied(action.matchId(), action.actorUserId(), action.targetZone()),
            loadSourceHolomem(action.matchId(), action.actorUserId(), action.sourceCardInstanceId())
        );
    }

    private CollabSourceHolomemSnapshot loadSourceHolomem(Long matchId, Long userId, Long cardInstanceId) {
        return jdbcTemplate.query(
            """
            SELECT id, match_card_id, zone, card_id, is_rested
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
                return new CollabSourceHolomemSnapshot(
                    rs.getLong("id"),
                    rs.getLong("match_card_id"),
                    rs.getString("card_id"),
                    normalize(rs.getString("zone")),
                    toBoolean(rs.getObject("is_rested"))
                );
            },
            matchId,
            userId,
            cardInstanceId
        );
    }

    private boolean hasDuplicateAction(CollabAction action) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'COLLAB'
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

    private boolean hasUsedCollabThisTurn(Long matchId, Long userId, int turnNumber) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'COLLAB'
            """,
            Integer.class,
            matchId,
            userId,
            turnNumber
        );
        return count != null && count > 0;
    }

    private int countTargetZoneOccupied(Long matchId, Long userId, String targetZone) {
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
            normalize(targetZone)
        );
        return count == null ? 0 : count;
    }

    private boolean isStageActionLocked(
        Long matchId,
        Long userId,
        int currentTurn,
        String actionKey,
        String zone,
        Long holomemId
    ) {
        if (matchId == null || userId == null || currentTurn <= 0 || !StringUtils.hasText(actionKey)) {
            return false;
        }
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
            currentTurn
        );
        String normalizedAction = normalize(actionKey);
        String normalizedZone = normalize(zone);
        for (String payloadText : payloads) {
            JsonNode payload = parseJson(payloadText);
            if (payload == null || payload.isNull()) {
                continue;
            }
            if (!matchesLockAction(payload, normalizedAction)) {
                continue;
            }
            if (!matchesLockZone(payload, normalizedZone)) {
                continue;
            }
            if (!matchesLockTargetHolomem(payload, holomemId)) {
                continue;
            }
            return true;
        }
        return false;
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

    private boolean matchesLockZone(JsonNode payload, String zone) {
        JsonNode zones = payload.get("zones");
        if (zones == null || !zones.isArray() || zones.isEmpty()) {
            return true;
        }
        for (JsonNode zoneNode : zones) {
            if (normalize(zoneNode.asText()).equals(zone)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLockTargetHolomem(JsonNode payload, Long holomemId) {
        JsonNode targetHolomemId = payload.get("targetHolomemId");
        return targetHolomemId == null || holomemId == null || targetHolomemId.asLong() == holomemId;
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

    private MatchPhase parsePhase(String phase) {
        if (!StringUtils.hasText(phase)) {
            return null;
        }
        try {
            return MatchPhase.valueOf(phase.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
