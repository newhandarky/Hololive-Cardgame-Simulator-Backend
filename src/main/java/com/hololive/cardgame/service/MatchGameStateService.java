package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.BoardZoneStateResponse;
import com.hololive.cardgame.dto.GameStateResponse;
import com.hololive.cardgame.dto.PendingDecisionCandidateResponse;
import com.hololive.cardgame.dto.PendingDecisionResponse;
import com.hololive.cardgame.dto.PlayerZoneStateResponse;
import com.hololive.cardgame.dto.RecentMatchActionResponse;
import com.hololive.cardgame.dto.ZoneCardInstanceResponse;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.entity.MatchPlayerEntity;
import com.hololive.cardgame.model.MatchPhase;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class MatchGameStateService {

    // 場地 1~9 映射，供前端直接依 slot 渲染。
    private static final Map<String, Integer> BOARD_ZONE_SLOT_INDEX = Map.of(
        "OSHI", 1,
        "CENTER", 2,
        "COLLAB", 3,
        "BACK", 4,
        "DECK", 5,
        "ARCHIVE", 6,
        "HOLOPOWER", 7,
        "CHEER_DECK", 8,
        "LIFE", 9
    );

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MatchGameStateService(
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

    @Transactional(readOnly = true)
    public GameStateResponse getGameStateForUser(Long matchId, Long userId) {
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalStateException("你不在此房間中");
        }
        GameStateResponse response = getGameState(matchId);
        response.getPendingDecisions().addAll(loadPendingDecisions(matchId, userId));
        return response;
    }

    @Transactional(readOnly = true)
    public GameStateResponse getGameState(Long matchId) {
        MatchEntity match = matchRepository.findById(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));

        List<MatchPlayerEntity> matchPlayers = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);
        Map<Long, PlayerZoneStateResponse> playerStates = new LinkedHashMap<>();
        for (MatchPlayerEntity player : matchPlayers) {
            playerStates.put(player.getUserId(), new PlayerZoneStateResponse(player.getUserId()));
        }

        // match_cards 回傳每張卡的實例與位置，前端不需要自行猜測區位資料。
        List<Map<String, Object>> matchCardRows = jdbcTemplate.queryForList(
            """
            SELECT
                owner_user_id,
                zone,
                id AS card_instance_id,
                card_id,
                COALESCE(order_index, ROW_NUMBER() OVER (PARTITION BY owner_user_id, zone ORDER BY id)) AS position_index,
                is_face_down
            FROM match_cards
            WHERE match_id = ?
            ORDER BY owner_user_id, zone, position_index, id
            """,
            matchId
        );
        for (Map<String, Object> row : matchCardRows) {
            Long ownerUserId = toLong(row.get("owner_user_id"));
            String zone = normalizeZone(row.get("zone"));
            PlayerZoneStateResponse playerState = playerStates.get(ownerUserId);
            if (playerState == null || !isMatchCardSupportedZone(zone)) {
                continue;
            }
            ZoneCardInstanceResponse card = new ZoneCardInstanceResponse(
                toLong(row.get("card_instance_id")),
                toStringValue(row.get("card_id")),
                zone,
                toInt(row.get("position_index")),
                ownerUserId,
                toBoolean(row.get("is_face_down"))
            );
            addCardToZone(playerState, card);
        }

        // 場上 Holomen 使用 match_holomems.zone（CENTER/COLLAB/BACK），並回填對應的 match_card 實例 ID。
        List<Map<String, Object>> stageRows = jdbcTemplate.queryForList(
            """
            SELECT
                h.owner_user_id,
                h.zone,
                mc.id AS card_instance_id,
                h.card_id,
                ROW_NUMBER() OVER (PARTITION BY h.owner_user_id, h.zone ORDER BY h.id) AS position_index,
                h.is_face_down,
                COALESCE(stack_info.stack_depth, 1) AS stack_depth,
                stack_info.stack_card_instance_ids
            FROM match_holomems h
            JOIN match_cards mc ON mc.id = h.match_card_id
            LEFT JOIN LATERAL (
                SELECT
                    COUNT(*)::int AS stack_depth,
                    ARRAY_AGG(s.match_card_id ORDER BY s.stack_order) AS stack_card_instance_ids
                FROM match_holomem_stack_cards s
                WHERE s.match_holomem_id = h.id
            ) stack_info ON TRUE
            WHERE h.match_id = ?
            ORDER BY h.owner_user_id, h.zone, position_index, h.id
            """,
            matchId
        );
        for (Map<String, Object> row : stageRows) {
            Long ownerUserId = toLong(row.get("owner_user_id"));
            String zone = normalizeZone(row.get("zone"));
            PlayerZoneStateResponse playerState = playerStates.get(ownerUserId);
            if (playerState == null) {
                continue;
            }
            ZoneCardInstanceResponse card = new ZoneCardInstanceResponse(
                toLong(row.get("card_instance_id")),
                toStringValue(row.get("card_id")),
                zone,
                toInt(row.get("position_index")),
                ownerUserId,
                toBoolean(row.get("is_face_down")),
                toInt(row.get("stack_depth")),
                toLongList(row.get("stack_card_instance_ids"), toLong(row.get("card_instance_id")))
            );
            addCardToZone(playerState, card);
        }

        GameStateResponse response = new GameStateResponse();
        response.setMatchId(match.getId());
        response.setRoomCode(match.getRoomCode());
        response.setStatus(match.getLobbyStatus());
        response.setPhase(parsePhase(match.getCurrentPhase()));
        response.setCurrentTurnPlayerId(match.getCurrentTurnPlayerId());
        response.setTurnNumber(match.getTurnNumber());
        response.getPlayers().addAll(playerStates.values());
        response.getRecentActions().addAll(loadRecentActions(matchId));
        return response;
    }

    private List<RecentMatchActionResponse> loadRecentActions(Long matchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT id,
                   user_id,
                   action_type,
                   turn_number,
                   action_order,
                   payload::text AS payload_text,
                   executed_at AS created_at
            FROM match_actions
            WHERE match_id = ?
            ORDER BY id DESC
            LIMIT 20
            """,
            matchId
        );
        List<RecentMatchActionResponse> actions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RecentMatchActionResponse action = new RecentMatchActionResponse();
            action.setActionId(toLong(row.get("id")));
            action.setUserId(toLong(row.get("user_id")));
            action.setActionType(toStringValue(row.get("action_type")));
            action.setTurnNumber(toInt(row.get("turn_number")));
            action.setActionOrder(toInt(row.get("action_order")));
            action.setPayload(parsePayloadJson(toStringValue(row.get("payload_text"))));
            action.setCreatedAt(toLocalDateTime(row.get("created_at")));
            actions.add(action);
        }
        return actions;
    }

    private List<PendingDecisionResponse> loadPendingDecisions(Long matchId, Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT id,
                   decision_type,
                   source_action_type,
                   source_card_instance_id,
                   source_card_id,
                   effect_type,
                   min_select,
                   max_select,
                   context_json::text AS context_text,
                   created_at
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
            ORDER BY id ASC
            LIMIT 5
            """,
            matchId,
            userId
        );
        List<PendingDecisionResponse> decisions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            PendingDecisionResponse decision = new PendingDecisionResponse();
            decision.setDecisionId(toLong(row.get("id")));
            decision.setDecisionType(toStringValue(row.get("decision_type")));
            decision.setSourceActionType(toStringValue(row.get("source_action_type")));
            decision.setSourceCardInstanceId(toLong(row.get("source_card_instance_id")));
            decision.setSourceCardId(toStringValue(row.get("source_card_id")));
            decision.setEffectType(toStringValue(row.get("effect_type")));
            decision.setMinSelect(toInt(row.get("min_select")));
            decision.setMaxSelect(toInt(row.get("max_select")));
            decision.setCreatedAt(toLocalDateTime(row.get("created_at")));

            JsonNode contextNode = parsePayloadJson(toStringValue(row.get("context_text")));
            decision.setTargetHolomemCardInstanceId(toLong(contextNode.path("targetHolomemCardInstanceId").asText(null)));
            decision.getCandidates().addAll(loadPendingDecisionCandidates(contextNode));
            decisions.add(decision);
        }
        return decisions;
    }

    private List<PendingDecisionCandidateResponse> loadPendingDecisionCandidates(JsonNode contextNode) {
        if (contextNode == null || contextNode.isNull()) {
            return List.of();
        }
        JsonNode candidateNodes = contextNode.path("candidateCards");
        if (!candidateNodes.isArray() || candidateNodes.isEmpty()) {
            return List.of();
        }
        List<PendingDecisionCandidateResponse> candidates = new ArrayList<>();
        for (JsonNode node : candidateNodes) {
            PendingDecisionCandidateResponse candidate = new PendingDecisionCandidateResponse();
            candidate.setCardInstanceId(toLong(node.path("cardInstanceId").asText(null)));
            candidate.setCardId(toStringValue(node.path("cardId").asText(null)));
            candidate.setName(toStringValue(node.path("name").asText(null)));
            candidate.setCardType(toStringValue(node.path("cardType").asText(null)));
            candidate.setLevelType(toStringValue(node.path("levelType").asText(null)));
            candidate.setZone(toStringValue(node.path("zone").asText(null)));
            candidates.add(candidate);
        }
        return candidates;
    }

    private boolean isMatchCardSupportedZone(String zone) {
        return "HAND".equals(zone) || BOARD_ZONE_SLOT_INDEX.containsKey(zone);
    }

    private void addCardToZone(PlayerZoneStateResponse playerState, ZoneCardInstanceResponse card) {
        if (playerState == null || card == null) {
            return;
        }
        String zone = normalizeZone(card.getZone());
        if ("HAND".equals(zone)) {
            playerState.getHandCards().add(card);
            playerState.setHandCount(playerState.getHandCards().size());
            return;
        }
        BoardZoneStateResponse boardZone = findBoardZone(playerState, zone);
        if (boardZone == null) {
            return;
        }
        boardZone.getCards().add(card);
        applyBoardZoneCount(playerState, zone, boardZone.getCards().size());
    }

    private BoardZoneStateResponse findBoardZone(PlayerZoneStateResponse playerState, String zone) {
        return playerState.getBoardZones().stream()
            .filter(boardZone -> zone.equals(boardZone.getZone()))
            .findFirst()
            .orElse(null);
    }

    private void applyBoardZoneCount(PlayerZoneStateResponse playerState, String zone, int count) {
        switch (zone) {
            case "OSHI" -> playerState.setOshiCount(count);
            case "CENTER" -> playerState.setCenterCount(count);
            case "COLLAB" -> playerState.setCollabCount(count);
            case "BACK" -> playerState.setBackCount(count);
            case "DECK" -> playerState.setDeckCount(count);
            case "ARCHIVE" -> playerState.setArchiveCount(count);
            case "HOLOPOWER" -> playerState.setHolopowerCount(count);
            case "CHEER_DECK" -> playerState.setCheerDeckCount(count);
            case "LIFE" -> playerState.setLifeCount(count);
            default -> {
                // 非 P0 所需區位先忽略
            }
        }
    }

    private MatchPhase parsePhase(String rawPhase) {
        if (!StringUtils.hasText(rawPhase)) {
            return MatchPhase.RESET;
        }
        try {
            return MatchPhase.valueOf(rawPhase.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown match current_phase={}, fallback RESET", rawPhase);
            return MatchPhase.RESET;
        }
    }

    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String toStringValue(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private List<Long> toLongList(Object value, Long fallbackSingle) {
        if (value == null) {
            return fallbackSingle == null ? List.of() : List.of(fallbackSingle);
        }
        if (value instanceof Array sqlArray) {
            try {
                Object rawArray = sqlArray.getArray();
                if (rawArray instanceof Object[] array) {
                    List<Long> values = new ArrayList<>();
                    for (Object item : array) {
                        Long converted = toLong(item);
                        if (converted != null) {
                            values.add(converted);
                        }
                    }
                    if (!values.isEmpty()) {
                        return values;
                    }
                }
            } catch (SQLException ignored) {
                // fallback to single top instance
            }
        }
        Long converted = toLong(value);
        if (converted != null) {
            return List.of(converted);
        }
        return fallbackSingle == null ? List.of() : List.of(fallbackSingle);
    }

    private JsonNode parsePayloadJson(String value) {
        if (!StringUtils.hasText(value)) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.valueToTree(Map.of("raw", value));
        }
    }

    private java.time.LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.time.LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
