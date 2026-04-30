package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlayCardLegacyResolutionBridge {

    private static final Set<String> ACTION_LOCK_KEYS = Set.of("PLAY_CARD", "PLAY_TO_STAGE", "STAGE");

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PendingDecisionReader pendingDecisionReader;

    public PlayCardLegacyResolutionBridge(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.pendingDecisionReader = new PendingDecisionReader(jdbcTemplate);
    }

    public PlayCardValidationContext loadValidationContext(PlayCardAction action) {
        if (action == null) {
            throw new IllegalArgumentException("PLAY_CARD action 不可為空");
        }
        MatchEntity match = matchRepository.findByIdForUpdate(action.matchId())
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
        if (!matchPlayerRepository.existsByMatchIdAndUserId(action.matchId(), action.actorUserId())) {
            throw new IllegalArgumentException("你不在此房間中");
        }

        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        MatchPhase currentPhase = parsePhase(match.getCurrentPhase());
        String targetZone = normalize(action.targetZone());
        return new PlayCardValidationContext(
            match,
            action.actorUserId(),
            turnNumber,
            match.getCurrentTurnPlayerId(),
            currentPhase,
            String.valueOf(match.getStatus()),
            String.valueOf(match.getLobbyStatus()),
            hasDuplicateAction(action),
            hasPendingDecision(action.matchId(), action.actorUserId()) || hasAnyPendingDecision(action.matchId()),
            isActionLocked(action.matchId(), action.actorUserId(), turnNumber),
            isActorMulliganDone(action.matchId(), action.actorUserId()),
            hasOpeningCenterPlaced(action.matchId(), action.actorUserId()),
            countTargetZone(action.matchId(), action.actorUserId(), targetZone),
            loadSourceCard(action.matchId(), action.actorUserId(), action.cardInstanceId())
        );
    }

    private PlayCardSourceCardSnapshot loadSourceCard(Long matchId, Long userId, Long cardInstanceId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, mc.zone, m.card_id IS NOT NULL AS member_card, m.level_type
            FROM match_cards mc
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new PlayCardSourceCardSnapshot(
                    rs.getLong("id"),
                    rs.getString("card_id"),
                    normalize(rs.getString("zone")),
                    toBoolean(rs.getObject("member_card")),
                    normalize(rs.getString("level_type"))
                );
            },
            matchId,
            userId,
            cardInstanceId
        );
    }

    private boolean hasDuplicateAction(PlayCardAction action) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type IN ('OPENING_SET_CENTER', 'OPENING_SET_BACK', 'PLAY_TO_STAGE')
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
        return pendingDecisionReader.hasAnyPendingDecision(matchId, userId);
    }

    private boolean hasAnyPendingDecision(Long matchId) {
        return pendingDecisionReader.hasAnyPendingDecision(matchId);
    }

    private boolean isActorMulliganDone(Long matchId, Long userId) {
        Boolean value = jdbcTemplate.query(
            """
            SELECT mulligan_done
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getBoolean("mulligan_done") : false,
            matchId,
            userId
        );
        return Boolean.TRUE.equals(value);
    }

    private boolean hasOpeningCenterPlaced(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            """,
            Integer.class,
            matchId,
            userId
        );
        return count != null && count > 0;
    }

    private int countTargetZone(Long matchId, Long userId, String targetZone) {
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
            targetZone
        );
        return count == null ? 0 : count;
    }

    private boolean isActionLocked(Long matchId, Long userId, int currentTurn) {
        if (matchId == null || userId == null || currentTurn <= 0) {
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
        for (String payloadText : payloads) {
            JsonNode payload = parseJson(payloadText);
            if (payload == null || payload.isNull()) {
                continue;
            }
            if (matchesAnyLockAction(payload)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyLockAction(JsonNode payload) {
        JsonNode actions = payload.get("actions");
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            return true;
        }
        for (JsonNode actionNode : actions) {
            if (ACTION_LOCK_KEYS.contains(normalize(actionNode.asText()))) {
                return true;
            }
        }
        return false;
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
