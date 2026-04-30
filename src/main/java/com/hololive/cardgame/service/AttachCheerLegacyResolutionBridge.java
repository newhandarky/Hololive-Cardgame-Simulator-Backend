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
public class AttachCheerLegacyResolutionBridge {

    private static final Set<String> ACTION_LOCK_KEYS = Set.of("ATTACH_CHEER", "ATTACH", "CHEER");

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PendingDecisionReader pendingDecisionReader;

    public AttachCheerLegacyResolutionBridge(
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

    public AttachCheerValidationContext loadValidationContext(AttachCheerAction action) {
        if (action == null) {
            throw new IllegalArgumentException("ATTACH_CHEER action 不可為空");
        }
        MatchEntity match = matchRepository.findByIdForUpdate(action.matchId())
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
        if (!matchPlayerRepository.existsByMatchIdAndUserId(action.matchId(), action.actorUserId())) {
            throw new IllegalArgumentException("你不在此房間中");
        }

        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        MatchPhase currentPhase = parsePhase(match.getCurrentPhase());
        AttachCheerTargetHolomemSnapshot targetHolomem = loadTargetHolomem(
            action.matchId(),
            action.actorUserId(),
            action.targetHolomemCardInstanceId()
        );
        return new AttachCheerValidationContext(
            match,
            action.actorUserId(),
            turnNumber,
            match.getCurrentTurnPlayerId(),
            currentPhase,
            String.valueOf(match.getStatus()),
            String.valueOf(match.getLobbyStatus()),
            hasDuplicateAction(action),
            hasPendingDecision(action.matchId(), action.actorUserId()) || hasAnyPendingDecision(action.matchId()),
            isActionLocked(action.matchId(), action.actorUserId(), turnNumber, targetHolomem),
            loadSourceCard(action.matchId(), action.actorUserId(), action.cheerCardInstanceId()),
            targetHolomem
        );
    }

    private AttachCheerSourceCardSnapshot loadSourceCard(Long matchId, Long userId, Long cardInstanceId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, mc.zone, cc.card_id IS NOT NULL AS cheer_card
            FROM match_cards mc
            LEFT JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new AttachCheerSourceCardSnapshot(
                    rs.getLong("id"),
                    rs.getString("card_id"),
                    normalize(rs.getString("zone")),
                    toBoolean(rs.getObject("cheer_card"))
                );
            },
            matchId,
            userId,
            cardInstanceId
        );
    }

    private AttachCheerTargetHolomemSnapshot loadTargetHolomem(Long matchId, Long userId, Long cardInstanceId) {
        return jdbcTemplate.query(
            """
            SELECT id, match_card_id, card_id, zone
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
                return new AttachCheerTargetHolomemSnapshot(
                    rs.getLong("id"),
                    rs.getLong("match_card_id"),
                    rs.getString("card_id"),
                    normalize(rs.getString("zone"))
                );
            },
            matchId,
            userId,
            cardInstanceId
        );
    }

    private boolean hasDuplicateAction(AttachCheerAction action) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'ATTACH_CHEER'
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

    private boolean isActionLocked(
        Long matchId,
        Long userId,
        int currentTurn,
        AttachCheerTargetHolomemSnapshot targetHolomem
    ) {
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
            if (!matchesAnyLockAction(payload)) {
                continue;
            }
            if (!matchesLockTargetHolomem(payload, targetHolomem == null ? null : targetHolomem.holomemId())) {
                continue;
            }
            return true;
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
