package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchTurnLifecycleService {

    private static final String ACTION_TYPE_OPENING_SETUP_DONE = "OPENING_SETUP_DONE";
    private static final String ACTION_TYPE_ADVANCE_PHASE = "ADVANCE_PHASE";
    private static final String ACTION_TYPE_DRAW_TURN = "DRAW_TURN";
    private static final String ACTION_TYPE_TURN_CHEER = "TURN_CHEER";
    private static final String INTERACTION_TYPE_DRAW_REVEAL = "DRAW_REVEAL";
    private static final String INTERACTION_TYPE_LIVE_START = "LIVE_START";
    private static final String INTERACTION_TYPE_SEND_CHEER = "SEND_CHEER";
    private static final String INTERACTION_TYPE_TURN_START = "TURN_START";
    private static final String PENDING_STATUS = "PENDING";

    private final MatchRepository matchRepository;
    private final MatchActionRepository matchActionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MatchTurnLifecycleService(
        MatchRepository matchRepository,
        MatchActionRepository matchActionRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.matchRepository = matchRepository;
        this.matchActionRepository = matchActionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void completeEndTurn(
        MatchEntity match,
        Long actingUserId,
        Long opponentUserId,
        int turnNumber,
        int clearedEffectCount,
        int resetRestedCount,
        Map<String, Object> centerReplenishSummary
    ) {
        if (
            !"active".equalsIgnoreCase(asText(match == null ? null : match.getStatus())) ||
            !LobbyMatchStatus.STARTED.name().equalsIgnoreCase(asText(match == null ? null : match.getLobbyStatus()))
        ) {
            throw new IllegalStateException("END_TURN 結算流程異常：對戰已非進行中狀態");
        }

        jdbcTemplate.update(
            """
            UPDATE match_players
            SET skill_used_this_turn = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            match.getId(),
            opponentUserId
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", actingUserId);
        payload.put("toUserId", opponentUserId);
        payload.put("clearedExpiredTurnEffects", clearedEffectCount);
        payload.put("resetRestedCount", resetRestedCount);
        payload.put("centerReplenish", centerReplenishSummary);

        int nextTurnNumber = turnNumber + 1;
        payload.put("nextTurnNumber", nextTurnNumber);

        appendAction(match, actingUserId, "END_TURN", toJson(payload), turnNumber);

        match.setCurrentTurnPlayerId(opponentUserId);
        match.setTurnNumber(nextTurnNumber);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setUpdatedAt(LocalDateTime.now());
        matchRepository.saveAndFlush(match);

        Long interactionId = createTurnStartPendingInteraction(match.getId(), opponentUserId, nextTurnNumber);
        if (interactionId != null) {
            Map<String, Object> interactionPayload = new LinkedHashMap<>();
            interactionPayload.put("interactionId", interactionId);
            interactionPayload.put("interactionType", INTERACTION_TYPE_TURN_START);
            interactionPayload.put("sourceActionType", INTERACTION_TYPE_TURN_START);
            appendAction(
                match,
                opponentUserId,
                "INTERACTION_PENDING",
                toJson(interactionPayload),
                nextTurnNumber
            );
        }
    }

    public void completeOpeningSetup(MatchEntity match, Long userId, int turnNumber) {
        if (match == null || userId == null) {
            throw new IllegalArgumentException("OPENING_SETUP_DONE 結算流程缺少必要參數");
        }
        Long matchId = match.getId();
        if (!hasOpeningCenterPlaced(matchId, userId)) {
            throw new IllegalStateException("請先放置開場 CENTER Holomem");
        }
        if (hasOpeningSetupFinished(matchId, userId)) {
            throw new IllegalStateException("你已完成開場設置");
        }

        appendAction(
            match,
            userId,
            ACTION_TYPE_OPENING_SETUP_DONE,
            toJson(Map.of("userId", userId)),
            turnNumber
        );

        Long nextUserId = resolveNextOpeningSetupUser(match, userId);
        match.setCurrentTurnPlayerId(nextUserId != null ? nextUserId : match.getPlayerAId());
        match.setCurrentPhase(MatchPhase.RESET.name());
        match.setUpdatedAt(LocalDateTime.now());
        matchRepository.saveAndFlush(match);
        if (nextUserId == null) {
            appendLiveStartPendingInteraction(match, match.getPlayerAId(), turnNumber);
        }
    }

    public void beginTurnCheer(MatchEntity match, Long userId, int turnNumber, Long interactionId) {
        if (match == null || userId == null || interactionId == null || interactionId <= 0) {
            throw new IllegalArgumentException("TURN_CHEER 結算流程缺少必要參數");
        }
        match.setCurrentPhase(MatchPhase.CHEER.name());
        match.setUpdatedAt(LocalDateTime.now());
        matchRepository.saveAndFlush(match);

        Map<String, Object> interactionPayload = new LinkedHashMap<>();
        interactionPayload.put("interactionId", interactionId);
        interactionPayload.put("interactionType", INTERACTION_TYPE_SEND_CHEER);
        interactionPayload.put("sourceActionType", ACTION_TYPE_TURN_CHEER);
        appendAction(
            match,
            userId,
            "INTERACTION_PENDING",
            toJson(interactionPayload),
            turnNumber
        );
    }

    public void beginDrawTurn(
        MatchEntity match,
        Long userId,
        int turnNumber,
        Long drawnCardInstanceId,
        Long drawInteractionId
    ) {
        if (match == null || userId == null || drawnCardInstanceId == null || drawnCardInstanceId <= 0) {
            throw new IllegalArgumentException("DRAW_TURN 結算流程缺少必要參數");
        }
        match.setCurrentPhase(MatchPhase.DRAW.name());
        match.setUpdatedAt(LocalDateTime.now());
        matchRepository.saveAndFlush(match);

        if (drawInteractionId == null || drawInteractionId <= 0) {
            return;
        }
        Map<String, Object> interactionPayload = new LinkedHashMap<>();
        interactionPayload.put("interactionId", drawInteractionId);
        interactionPayload.put("interactionType", INTERACTION_TYPE_DRAW_REVEAL);
        interactionPayload.put("sourceActionType", ACTION_TYPE_DRAW_TURN);
        interactionPayload.put("drawnCardInstanceId", drawnCardInstanceId);
        appendAction(
            match,
            userId,
            "INTERACTION_PENDING",
            toJson(interactionPayload),
            turnNumber
        );
    }

    public void confirmTurnStartDecision(MatchEntity match, Long userId, int turnNumber, Long decisionId) {
        completeInteractionDecision(
            match,
            userId,
            turnNumber,
            decisionId,
            INTERACTION_TYPE_TURN_START,
            INTERACTION_TYPE_TURN_START,
            MatchPhase.DRAW,
            null
        );
    }

    public void confirmLiveStartDecision(MatchEntity match, Long userId, int turnNumber, Long decisionId) {
        if (match == null || match.getId() == null || userId == null || decisionId == null || decisionId <= 0) {
            throw new IllegalArgumentException("LIVE_START 決策結算流程缺少必要參數");
        }
        Long matchId = match.getId();
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id IN (?, ?)
              AND zone IN ('CENTER', 'BACK')
            """,
            matchId,
            match.getPlayerAId(),
            match.getPlayerBId()
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id IN (?, ?)
              AND zone = 'STAGE'
              AND id IN (
                SELECT match_card_id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id IN (?, ?)
                  AND zone IN ('CENTER', 'BACK')
              )
            """,
            matchId,
            match.getPlayerAId(),
            match.getPlayerBId(),
            matchId,
            match.getPlayerAId(),
            match.getPlayerBId()
        );

        match.setCurrentTurnPlayerId(match.getPlayerAId());
        completeInteractionDecision(
            match,
            userId,
            turnNumber,
            decisionId,
            INTERACTION_TYPE_LIVE_START,
            INTERACTION_TYPE_LIVE_START,
            MatchPhase.MAIN,
            null
        );

        Long turnStartInteractionId = createTurnStartPendingInteraction(matchId, match.getPlayerAId(), turnNumber);
        if (turnStartInteractionId == null) {
            return;
        }
        Map<String, Object> interactionPayload = new LinkedHashMap<>();
        interactionPayload.put("interactionId", turnStartInteractionId);
        interactionPayload.put("interactionType", INTERACTION_TYPE_TURN_START);
        interactionPayload.put("sourceActionType", INTERACTION_TYPE_TURN_START);
        appendAction(
            match,
            match.getPlayerAId(),
            "INTERACTION_PENDING",
            toJson(interactionPayload),
            turnNumber
        );
    }

    public void confirmDrawRevealDecision(
        MatchEntity match,
        Long userId,
        int turnNumber,
        Long decisionId,
        MatchPhase nextPhase,
        Long drawnCardInstanceId,
        String drawnCardId,
        Map<String, Object> additionalPayload
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("drawnCardInstanceId", drawnCardInstanceId);
        payload.put("drawnCardId", drawnCardId);
        if (additionalPayload != null && !additionalPayload.isEmpty()) {
            payload.putAll(additionalPayload);
        }
        completeInteractionDecision(
            match,
            userId,
            turnNumber,
            decisionId,
            INTERACTION_TYPE_DRAW_REVEAL,
            ACTION_TYPE_DRAW_TURN,
            nextPhase,
            payload
        );
    }

    public void advancePhase(
        MatchEntity match,
        Long userId,
        int turnNumber,
        MatchPhase nextPhase,
        Map<String, Object> payload
    ) {
        if (match == null || userId == null || nextPhase == null) {
            throw new IllegalArgumentException("ADVANCE_PHASE 結算流程缺少必要參數");
        }
        match.setCurrentPhase(nextPhase.name());
        match.setUpdatedAt(LocalDateTime.now());
        matchRepository.saveAndFlush(match);

        appendAction(
            match,
            userId,
            ACTION_TYPE_ADVANCE_PHASE,
            toJson(payload == null ? Map.of() : payload),
            turnNumber
        );
    }

    private void completeInteractionDecision(
        MatchEntity match,
        Long userId,
        int turnNumber,
        Long decisionId,
        String interactionType,
        String sourceActionType,
        MatchPhase nextPhase,
        Map<String, Object> additionalPayload
    ) {
        if (
            match == null ||
            userId == null ||
            decisionId == null ||
            decisionId <= 0 ||
            nextPhase == null ||
            !StringUtils.hasText(interactionType) ||
            !StringUtils.hasText(sourceActionType)
        ) {
            throw new IllegalArgumentException("互動決策結算流程缺少必要參數");
        }
        match.setCurrentPhase(nextPhase.name());
        match.setUpdatedAt(LocalDateTime.now());
        matchRepository.saveAndFlush(match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", decisionId);
        payload.put("interactionType", interactionType);
        payload.put("sourceActionType", sourceActionType);
        if (additionalPayload != null && !additionalPayload.isEmpty()) {
            payload.putAll(additionalPayload);
        }
        appendAction(
            match,
            userId,
            "INTERACTION_CONFIRMED",
            toJson(payload),
            turnNumber
        );
    }

    public int resetRestedHolomemsForTurnStart(Long matchId, Long userId, int currentTurn) {
        if (matchId == null || userId == null || currentTurn <= 0) {
            return 0;
        }
        List<Map<String, Object>> restedRows = jdbcTemplate.queryForList(
            """
            SELECT id, zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND is_rested = TRUE
            """,
            matchId,
            userId
        );
        int resetCount = 0;
        for (Map<String, Object> row : restedRows) {
            Long holomemId = asLong(row.get("id"));
            String zone = asText(row.get("zone"));
            if (holomemId == null) {
                continue;
            }
            if (isStageActionLocked(matchId, userId, currentTurn, "UNREST", zone, holomemId)) {
                continue;
            }
            int updated = jdbcTemplate.update(
                """
                UPDATE match_holomems
                SET is_rested = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND is_rested = TRUE
                """,
                holomemId,
                matchId,
                userId
            );
            resetCount += updated;
        }
        return resetCount;
    }

    public Map<String, Object> resolveEndTurnCenterReplenishCycle(Long matchId, Long userId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("applied", false);
        if (matchId == null || userId == null) {
            summary.put("reason", "INVALID_ARGUMENTS");
            summary.put("settled", false);
            summary.put("iterations", List.of());
            return summary;
        }
        List<Map<String, Object>> iterations = new ArrayList<>();
        boolean settled = false;
        boolean appliedAny = false;
        String reason = "CENTER_EXISTS";
        final int maxIterations = 8;
        for (int i = 0; i < maxIterations; i++) {
            if (hasCenterHolomem(matchId, userId)) {
                settled = true;
                reason = "CENTER_EXISTS";
                break;
            }
            Map<String, Object> step = autoReplenishCenterFromBackOnce(matchId, userId);
            iterations.add(step);
            if (toBoolean(step.get("applied"))) {
                appliedAny = true;
            }
            if (!toBoolean(step.get("applied"))) {
                reason = asText(step.get("reason"));
                settled = false;
                break;
            }
            reason = asText(step.get("reason"));
        }
        if (hasCenterHolomem(matchId, userId)) {
            settled = true;
            if (!appliedAny) {
                reason = "CENTER_EXISTS";
            }
        }
        summary.put("applied", appliedAny);
        summary.put("reason", reason);
        summary.put("settled", settled);
        summary.put("iterationCount", iterations.size());
        summary.put("iterations", iterations);
        return summary;
    }

    private Long createTurnStartPendingInteraction(Long matchId, Long userId, int turnNumber) {
        if (matchId == null || userId == null || userId <= 0) {
            return null;
        }
        if (hasAnyPendingDecision(matchId, userId)) {
            return null;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_TURN_START);
        context.put("title", "回合開始");
        context.put("message", "現在是你的回合。請先確認，接著依序進入抽牌與吶喊階段。");
        context.put("turnNumber", turnNumber);

        return jdbcTemplate.query(
            """
            INSERT INTO match_pending_decisions (
                match_id,
                user_id,
                decision_type,
                source_action_type,
                source_card_instance_id,
                source_card_id,
                effect_type,
                min_select,
                max_select,
                status,
                context_json
            ) VALUES (?, ?, ?, ?, NULL, NULL, ?, 1, 1, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_TURN_START,
            INTERACTION_TYPE_TURN_START,
            INTERACTION_TYPE_TURN_START,
            PENDING_STATUS,
            toJson(context)
        );
    }

    private void appendLiveStartPendingInteraction(MatchEntity match, Long userId, int turnNumber) {
        Long interactionId = createLiveStartPendingInteraction(match == null ? null : match.getId(), userId, turnNumber);
        if (interactionId == null) {
            return;
        }
        Map<String, Object> interactionPayload = new LinkedHashMap<>();
        interactionPayload.put("interactionId", interactionId);
        interactionPayload.put("interactionType", INTERACTION_TYPE_LIVE_START);
        interactionPayload.put("sourceActionType", INTERACTION_TYPE_LIVE_START);
        appendAction(
            match,
            userId,
            "INTERACTION_PENDING",
            toJson(interactionPayload),
            turnNumber
        );
    }

    private Long createLiveStartPendingInteraction(Long matchId, Long userId, int turnNumber) {
        if (matchId == null || userId == null || userId <= 0) {
            return null;
        }
        if (hasAnyPendingDecision(matchId, userId)) {
            return null;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_LIVE_START);
        context.put("title", "LIVE START!!");
        context.put("message", "雙方開場舞台已設置完成，確認後翻開 CENTER 與 BACK 並開始對戰。");
        context.put("turnNumber", turnNumber);
        return jdbcTemplate.query(
            """
            INSERT INTO match_pending_decisions (
                match_id,
                user_id,
                decision_type,
                source_action_type,
                source_card_instance_id,
                source_card_id,
                effect_type,
                min_select,
                max_select,
                status,
                context_json
            ) VALUES (?, ?, ?, ?, NULL, NULL, ?, 1, 1, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_LIVE_START,
            INTERACTION_TYPE_LIVE_START,
            INTERACTION_TYPE_LIVE_START,
            PENDING_STATUS,
            toJson(context)
        );
    }

    private boolean hasAnyPendingDecision(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = ?
            """,
            Integer.class,
            matchId,
            userId,
            PENDING_STATUS
        );
        return count != null && count > 0;
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
        if (payloads.isEmpty()) {
            return false;
        }
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
        if (targetHolomemId == null || targetHolomemId.isNull()) {
            return true;
        }
        Long expected = asLong(targetHolomemId.asText());
        if (expected == null || expected <= 0) {
            return true;
        }
        return holomemId != null && holomemId.equals(expected);
    }

    private Map<String, Object> autoReplenishCenterFromBackOnce(Long matchId, Long userId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("applied", false);
        if (matchId == null || userId == null) {
            summary.put("reason", "INVALID_ARGUMENTS");
            return summary;
        }
        if (hasCenterHolomem(matchId, userId)) {
            summary.put("reason", "CENTER_EXISTS");
            return summary;
        }
        Map<String, Object> preferredBack = jdbcTemplate.query(
            """
            SELECT id, match_card_id, card_id, is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
            ORDER BY CASE WHEN is_rested = FALSE THEN 0 ELSE 1 END, id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("is_rested", rs.getObject("is_rested"));
                return row;
            },
            matchId,
            userId
        );
        if (preferredBack == null) {
            summary.put("reason", "NO_BACK_HOLOMEM");
            return summary;
        }
        Long holomemId = asLong(preferredBack.get("id"));
        if (holomemId == null) {
            summary.put("reason", "BACK_HOLOMEM_INVALID");
            return summary;
        }
        int moved = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'CENTER',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
            """,
            holomemId,
            matchId,
            userId
        );
        if (moved != 1) {
            summary.put("reason", "MOVE_FAILED");
            return summary;
        }
        summary.put("applied", true);
        summary.put("reason", "CENTER_REPLENISHED");
        summary.put("targetHolomemId", holomemId);
        summary.put("targetHolomemCardInstanceId", asLong(preferredBack.get("match_card_id")));
        summary.put("targetCardId", asText(preferredBack.get("card_id")));
        summary.put("fromRested", toBoolean(preferredBack.get("is_rested")));
        return summary;
    }

    private boolean hasCenterHolomem(Long matchId, Long userId) {
        Integer centerCount = jdbcTemplate.queryForObject(
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
        return centerCount != null && centerCount > 0;
    }

    private boolean hasOpeningCenterPlaced(Long matchId, Long userId) {
        return hasCenterHolomem(matchId, userId);
    }

    private boolean hasOpeningSetupFinished(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = ?
            """,
            Integer.class,
            matchId,
            userId,
            ACTION_TYPE_OPENING_SETUP_DONE
        );
        return count != null && count > 0;
    }

    private Long resolveNextOpeningSetupUser(MatchEntity match, Long currentUserId) {
        if (match == null || currentUserId == null) {
            return null;
        }
        Long playerAId = match.getPlayerAId();
        Long playerBId = match.getPlayerBId();
        if (currentUserId.equals(playerAId) && playerBId != null && !hasOpeningSetupFinished(match.getId(), playerBId)) {
            return playerBId;
        }
        if (currentUserId.equals(playerBId) && playerAId != null && !hasOpeningSetupFinished(match.getId(), playerAId)) {
            return playerAId;
        }
        return null;
    }

    private void appendAction(
        MatchEntity match,
        Long userId,
        String actionType,
        String payload,
        int turnNumber
    ) {
        MatchActionEntity action = new MatchActionEntity();
        action.setMatchId(match.getId());
        action.setUserId(userId);
        action.setActionType(actionType);
        action.setPayload(payload);
        action.setTurnNumber(turnNumber);
        action.setActionOrder(matchActionRepository.findMaxActionOrderByTurn(match.getId(), turnNumber) + 1);
        action.setExecutedAt(LocalDateTime.now());
        matchActionRepository.save(action);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if (normalized.isEmpty()) {
                return false;
            }
            return "1".equals(normalized) || "true".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized);
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
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
}
