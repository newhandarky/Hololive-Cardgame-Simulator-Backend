package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.BoardZoneStateResponse;
import com.hololive.cardgame.dto.GameStateResponse;
import com.hololive.cardgame.dto.PendingDecisionCandidateResponse;
import com.hololive.cardgame.dto.PendingDecisionResponse;
import com.hololive.cardgame.dto.PendingInteractionResponse;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class MatchGameStateService {
    private static final Pattern SPECIAL_DAMAGE_PATTERN = Pattern.compile("特殊ダメージ\\s*(\\d+)");
    private static final Pattern DAMAGE_PATTERN = Pattern.compile("ダメージ\\s*(\\d+)");
    private static final Pattern ANY_NUMBER_PATTERN = Pattern.compile("(\\d+)");

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
    private final MatchEffectService matchEffectService;

    /**
     * 建立對戰狀態查詢服務，統整 DB 資料成前端可直接渲染的 GameState。
     */
    public MatchGameStateService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchEffectService matchEffectService
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.matchEffectService = matchEffectService;
    }

    @Transactional(readOnly = true)
    /**
     * 取得指定使用者視角的對戰狀態（包含其待處理決策與互動）。
     */
    public GameStateResponse getGameStateForUser(Long matchId, Long userId) {
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalStateException("你不在此房間中");
        }
        GameStateResponse response = getGameState(matchId);
        response.getPendingDecisions().addAll(loadPendingDecisions(matchId, userId));
        response.getPendingInteractions().addAll(loadPendingInteractions(matchId, userId));
        return response;
    }

    @Transactional(readOnly = true)
    /**
     * 取得對戰公共狀態（不含使用者專屬待辦）。
     */
    public GameStateResponse getGameState(Long matchId) {
        MatchEntity match = matchRepository.findById(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));

        List<MatchPlayerEntity> matchPlayers = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);
        Map<Long, PlayerZoneStateResponse> playerStates = new LinkedHashMap<>();
        for (MatchPlayerEntity player : matchPlayers) {
            PlayerZoneStateResponse state = new PlayerZoneStateResponse(player.getUserId());
            state.setMulliganUsed(player.isMulliganUsed());
            state.setMulliganDone(player.isMulliganDone());
            playerStates.put(player.getUserId(), state);
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
                h.id AS holomem_id,
                h.owner_user_id,
                h.zone,
                mc.id AS card_instance_id,
                h.card_id,
                ROW_NUMBER() OVER (PARTITION BY h.owner_user_id, h.zone ORDER BY h.id) AS position_index,
                h.is_face_down,
                h.damage_taken,
                m.hp AS base_max_hp,
                COALESCE(art_info.effect_json_text, '') AS primary_art_effect_json_text,
                COALESCE(cheer_info.cheer_count, 0) AS cheer_count,
                COALESCE(cheer_info.cheer_color_counts, '{}'::jsonb)::text AS cheer_color_counts_text,
                COALESCE(stack_info.stack_depth, 1) AS stack_depth,
                stack_info.stack_card_instance_ids,
                COALESCE(support_info.attached_support_count, 0) AS attached_support_count
            FROM match_holomems h
            JOIN match_cards mc ON mc.id = h.match_card_id
            JOIN member_cards m ON m.card_id = h.card_id
            LEFT JOIN LATERAL (
                SELECT effect_json::text AS effect_json_text
                FROM member_arts
                WHERE member_card_id = h.card_id
                ORDER BY order_index ASC, id ASC
                LIMIT 1
            ) art_info ON TRUE
            LEFT JOIN LATERAL (
                SELECT
                    COALESCE(SUM(color_counts.count), 0)::int AS cheer_count,
                    COALESCE(JSONB_OBJECT_AGG(color_counts.color, color_counts.count), '{}'::jsonb) AS cheer_color_counts
                FROM (
                    SELECT cc.color, COUNT(*)::int AS count
                    FROM match_holomem_cheers mhc
                    JOIN cheer_cards cc ON cc.card_id = mhc.cheer_card_id
                    WHERE mhc.match_holomem_id = h.id
                    GROUP BY cc.color
                ) color_counts
            ) cheer_info ON TRUE
            LEFT JOIN LATERAL (
                SELECT
                    COUNT(*)::int AS stack_depth,
                    ARRAY_AGG(s.match_card_id ORDER BY s.stack_order) AS stack_card_instance_ids
                FROM match_holomem_stack_cards s
                WHERE s.match_holomem_id = h.id
            ) stack_info ON TRUE
            LEFT JOIN LATERAL (
                SELECT COUNT(*)::int AS attached_support_count
                FROM match_holomem_supports hs
                WHERE hs.match_holomem_id = h.id
            ) support_info ON TRUE
            WHERE h.match_id = ?
            ORDER BY h.owner_user_id, h.zone, position_index, h.id
            """,
            matchId
        );
        Map<Long, Integer> activeTurnDamageModifiers = resolveActiveTurnDamageModifiers(
            matchId,
            match.getTurnNumber(),
            stageRows
        );
        for (Map<String, Object> row : stageRows) {
            Long ownerUserId = toLong(row.get("owner_user_id"));
            String zone = normalizeZone(row.get("zone"));
            PlayerZoneStateResponse playerState = playerStates.get(ownerUserId);
            if (playerState == null) {
                continue;
            }
            Long holomemId = toLong(row.get("holomem_id"));
            Integer damageTaken = toNullableInt(row.get("damage_taken"));
            Integer baseMaxHp = toNullableInt(row.get("base_max_hp"));
            int hpBonus = matchEffectService.resolveAttachedSupportHpBonus(matchId, holomemId)
                + matchEffectService.resolvePassiveGiftHpBonus(matchId, ownerUserId, holomemId);
            int artBonus = matchEffectService.resolveAttachedSupportArtBonus(matchId, holomemId);
            int turnDamageModifier = activeTurnDamageModifiers.getOrDefault(ownerUserId, 0);
            int adjustedMaxHp = Math.max((baseMaxHp == null ? 0 : baseMaxHp) + hpBonus, 0);
            int adjustedCurrentHp = Math.max(adjustedMaxHp - (damageTaken == null ? 0 : damageTaken), 0);
            int currentAttack = Math.max(
                resolveArtDamage(toStringValue(row.get("primary_art_effect_json_text"))) + artBonus + turnDamageModifier,
                0
            );
            ZoneCardInstanceResponse card = new ZoneCardInstanceResponse(
                toLong(row.get("card_instance_id")),
                toStringValue(row.get("card_id")),
                zone,
                toInt(row.get("position_index")),
                ownerUserId,
                toBoolean(row.get("is_face_down")),
                toInt(row.get("stack_depth")),
                toLongList(row.get("stack_card_instance_ids"), toLong(row.get("card_instance_id"))),
                adjustedCurrentHp,
                adjustedMaxHp,
                damageTaken,
                currentAttack,
                toNullableInt(row.get("cheer_count")),
                toColorCountMap(parsePayloadJson(toStringValue(row.get("cheer_color_counts_text")))),
                toNullableInt(row.get("attached_support_count"))
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

    /**
     * 載入近期行動紀錄（最新 20 筆）。
     */
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

    /**
     * 載入待處理的選牌型決策（CARD_SELECTION）。
     */
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
              AND decision_type = 'CARD_SELECTION'
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
            decision.getCandidates().addAll(loadPendingDecisionCandidates(matchId, contextNode));
            decisions.add(decision);
        }
        return decisions;
    }

    /**
     * 解析 decision context 中的候選卡片清單，並補齊場上統計資訊。
     */
    private List<PendingDecisionCandidateResponse> loadPendingDecisionCandidates(Long matchId, JsonNode contextNode) {
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
            candidate.setImageUrl(toStringValue(node.path("imageUrl").asText(null)));
            candidate.setCurrentHp(toNullableInt(node.path("currentHp").asText(null)));
            candidate.setMaxHp(toNullableInt(node.path("maxHp").asText(null)));
            candidate.setDamageTaken(toNullableInt(node.path("damageTaken").asText(null)));
            candidate.setCheerCount(toNullableInt(node.path("cheerCount").asText(null)));
            candidate.setCheerColorCounts(toColorCountMap(node.path("cheerColorCounts")));
            candidate.setAttachedSupportCount(toNullableInt(node.path("attachedSupportCount").asText(null)));
            candidates.add(candidate);
        }
        enrichPendingCandidateStageStats(matchId, candidates);
        return candidates;
    }

    /**
     * 載入待處理互動（非 CARD_SELECTION），例如 trigger confirm、放置選項等。
     */
    private List<PendingInteractionResponse> loadPendingInteractions(Long matchId, Long userId) {
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
              AND decision_type <> 'CARD_SELECTION'
            ORDER BY id ASC
            LIMIT 5
            """,
            matchId,
            userId
        );
        List<PendingInteractionResponse> interactions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            JsonNode contextNode = parsePayloadJson(toStringValue(row.get("context_text")));
            PendingInteractionResponse interaction = new PendingInteractionResponse();
            interaction.setInteractionId(toLong(row.get("id")));
            interaction.setInteractionType(resolveInteractionType(row, contextNode));
            interaction.setSourceActionType(toStringValue(row.get("source_action_type")));
            interaction.setSourceCardInstanceId(toLong(row.get("source_card_instance_id")));
            interaction.setSourceCardId(toStringValue(row.get("source_card_id")));
            interaction.setEffectType(toStringValue(row.get("effect_type")));
            interaction.setMinSelect(toInt(row.get("min_select")));
            interaction.setMaxSelect(toInt(row.get("max_select")));
            interaction.setTargetHolomemCardInstanceId(toLong(contextNode.path("targetHolomemCardInstanceId").asText(null)));
            interaction.setTitle(toStringValue(contextNode.path("title").asText(null)));
            interaction.setMessage(toStringValue(contextNode.path("message").asText(null)));
            interaction.setLookedCardInstanceId(toLong(contextNode.path("lookedCardInstanceId").asText(null)));
            interaction.setLookedCardId(toStringValue(contextNode.path("lookedCardId").asText(null)));
            JsonNode placementOptionsNode = contextNode.path("placementOptions");
            if (placementOptionsNode.isArray()) {
                for (JsonNode optionNode : placementOptionsNode) {
                    String option = toStringValue(optionNode.asText(null));
                    if (StringUtils.hasText(option)) {
                        interaction.getPlacementOptions().add(option.trim().toUpperCase(Locale.ROOT));
                    }
                }
            }
            interaction.setCreatedAt(toLocalDateTime(row.get("created_at")));
            interaction.getCards().addAll(loadPendingInteractionCards(matchId, contextNode));
            interactions.add(interaction);
        }
        return interactions;
    }

    /**
     * 決定互動類型，優先採用 context.interactionType，其次回退 decision_type。
     */
    private String resolveInteractionType(Map<String, Object> row, JsonNode contextNode) {
        String contextType = toStringValue(contextNode.path("interactionType").asText(null));
        if (StringUtils.hasText(contextType)) {
            return contextType.trim().toUpperCase(Locale.ROOT);
        }
        return normalizeZone(row.get("decision_type"));
    }

    /**
     * 讀取 pending interaction 的卡片列表，若缺少 cards 欄位則回退 candidateCards。
     */
    private List<PendingDecisionCandidateResponse> loadPendingInteractionCards(Long matchId, JsonNode contextNode) {
        if (contextNode == null || contextNode.isNull()) {
            return List.of();
        }
        JsonNode cardNodes = contextNode.path("cards");
        if (!cardNodes.isArray() || cardNodes.isEmpty()) {
            return loadPendingDecisionCandidates(matchId, contextNode);
        }
        List<PendingDecisionCandidateResponse> cards = new ArrayList<>();
        for (JsonNode node : cardNodes) {
            PendingDecisionCandidateResponse card = new PendingDecisionCandidateResponse();
            card.setCardInstanceId(toLong(node.path("cardInstanceId").asText(null)));
            card.setCardId(toStringValue(node.path("cardId").asText(null)));
            card.setName(toStringValue(node.path("name").asText(null)));
            card.setCardType(toStringValue(node.path("cardType").asText(null)));
            card.setLevelType(toStringValue(node.path("levelType").asText(null)));
            card.setZone(toStringValue(node.path("zone").asText(null)));
            card.setImageUrl(toStringValue(node.path("imageUrl").asText(null)));
            card.setCurrentHp(toNullableInt(node.path("currentHp").asText(null)));
            card.setMaxHp(toNullableInt(node.path("maxHp").asText(null)));
            card.setDamageTaken(toNullableInt(node.path("damageTaken").asText(null)));
            card.setCheerCount(toNullableInt(node.path("cheerCount").asText(null)));
            card.setCheerColorCounts(toColorCountMap(node.path("cheerColorCounts")));
            card.setAttachedSupportCount(toNullableInt(node.path("attachedSupportCount").asText(null)));
            cards.add(card);
        }
        enrichPendingCandidateStageStats(matchId, cards);
        return cards;
    }

    /**
     * 為候選卡補齊場上統計：HP、エール顏色計數、附加支援數。
     */
    private void enrichPendingCandidateStageStats(Long matchId, List<PendingDecisionCandidateResponse> candidates) {
        if (matchId == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        Set<Long> cardInstanceIds = candidates.stream()
            .map(PendingDecisionCandidateResponse::getCardInstanceId)
            .filter(id -> id != null && id > 0)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (cardInstanceIds.isEmpty()) {
            return;
        }

        String placeholders = cardInstanceIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        List<Object> params = new ArrayList<>();
        params.add(matchId);
        params.addAll(cardInstanceIds);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT h.match_card_id AS card_instance_id,
                   h.id AS holomem_id,
                   h.damage_taken,
                   m.hp AS base_max_hp,
                   COALESCE(cheer_info.cheer_count, 0) AS cheer_count,
                   COALESCE(cheer_info.cheer_color_counts, '{}'::jsonb)::text AS cheer_color_counts_text,
                   COALESCE(support_info.attached_support_count, 0) AS attached_support_count
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            LEFT JOIN LATERAL (
                SELECT
                    COALESCE(SUM(color_counts.count), 0)::int AS cheer_count,
                    COALESCE(JSONB_OBJECT_AGG(color_counts.color, color_counts.count), '{}'::jsonb) AS cheer_color_counts
                FROM (
                    SELECT cc.color, COUNT(*)::int AS count
                    FROM match_holomem_cheers mhc
                    JOIN cheer_cards cc ON cc.card_id = mhc.cheer_card_id
                    WHERE mhc.match_holomem_id = h.id
                    GROUP BY cc.color
                ) color_counts
            ) cheer_info ON TRUE
            LEFT JOIN LATERAL (
                SELECT COUNT(*)::int AS attached_support_count
                FROM match_holomem_supports hs
                WHERE hs.match_holomem_id = h.id
            ) support_info ON TRUE
            WHERE h.match_id = ?
              AND h.match_card_id IN (%s)
            """.formatted(placeholders),
            params.toArray()
        );
        if (rows.isEmpty()) {
            return;
        }

        Map<Long, Map<String, Object>> statsByCardInstance = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long cardInstanceId = toLong(row.get("card_instance_id"));
            if (cardInstanceId == null || cardInstanceId <= 0) {
                continue;
            }
            statsByCardInstance.put(cardInstanceId, row);
        }
        if (statsByCardInstance.isEmpty()) {
            return;
        }

        for (PendingDecisionCandidateResponse candidate : candidates) {
            if (candidate == null || candidate.getCardInstanceId() == null) {
                continue;
            }
            Map<String, Object> stats = statsByCardInstance.get(candidate.getCardInstanceId());
            if (stats == null) {
                continue;
            }
            Long holomemId = toLong(stats.get("holomem_id"));
            Integer damageTaken = toNullableInt(stats.get("damage_taken"));
            Integer baseMaxHp = toNullableInt(stats.get("base_max_hp"));
            int hpBonus = matchEffectService.resolveAttachedSupportHpBonus(matchId, holomemId)
                + matchEffectService.resolvePassiveGiftHpBonus(matchId, candidate.getOwnerUserId(), holomemId);
            int adjustedMaxHp = Math.max((baseMaxHp == null ? 0 : baseMaxHp) + hpBonus, 0);
            int adjustedCurrentHp = Math.max(adjustedMaxHp - (damageTaken == null ? 0 : damageTaken), 0);
            candidate.setCurrentHp(adjustedCurrentHp);
            candidate.setMaxHp(adjustedMaxHp);
            candidate.setDamageTaken(damageTaken);
            candidate.setCheerCount(toNullableInt(stats.get("cheer_count")));
            JsonNode colorCountsNode = parsePayloadJson(toStringValue(stats.get("cheer_color_counts_text")));
            candidate.setCheerColorCounts(toColorCountMap(colorCountsNode));
            candidate.setAttachedSupportCount(toNullableInt(stats.get("attached_support_count")));
        }
    }

    /**
     * 將 JSON 物件轉成 color -> count 映射。
     */
    private Map<String, Integer> toColorCountMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String color = normalizeZone(entry.getKey());
            if (!StringUtils.hasText(color)) {
                return;
            }
            Integer count = toNullableInt(entry.getValue().asText(null));
            if (count == null || count <= 0) {
                return;
            }
            result.put(color, count);
        });
        return result.isEmpty() ? Map.of() : result;
    }

    /**
     * 判斷 match_cards 區位是否為目前前端支援顯示的區位。
     */
    private boolean isMatchCardSupportedZone(String zone) {
        return "HAND".equals(zone) || BOARD_ZONE_SLOT_INDEX.containsKey(zone);
    }

    /**
     * 將卡片加入對應玩家區位容器並同步更新區位計數。
     */
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

    /**
     * 從玩家 boardZones 中尋找指定區位。
     */
    private BoardZoneStateResponse findBoardZone(PlayerZoneStateResponse playerState, String zone) {
        return playerState.getBoardZones().stream()
            .filter(boardZone -> zone.equals(boardZone.getZone()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 依區位更新玩家的各區卡片數欄位。
     */
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

    /**
     * 解析 match current_phase，未知值時回退 RESET。
     */
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

    /**
     * 區位字串正規化（trim + uppercase）。
     */
    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 安全轉 int，不可轉時回 0。
     */
    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    /**
     * 安全轉 nullable int，不可轉時回 null。
     */
    private Integer toNullableInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 計算當前仍生效的回合傷害修正（match_turn_effects）。
     */
    private Map<Long, Integer> resolveActiveTurnDamageModifiers(
        Long matchId,
        Integer turnNumber,
        List<Map<String, Object>> stageRows
    ) {
        if (matchId == null || stageRows == null || stageRows.isEmpty()) {
            return Map.of();
        }
        int currentTurn = turnNumber == null || turnNumber <= 0 ? 1 : turnNumber;
        Set<Long> ownerUserIds = stageRows.stream()
            .map(row -> toLong(row.get("owner_user_id")))
            .filter(id -> id != null && id > 0)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ownerUserIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = ownerUserIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        List<Object> params = new ArrayList<>();
        params.add(matchId);
        params.add(currentTurn);
        params.addAll(ownerUserIds);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT affected_user_id,
                   COALESCE(SUM(modifier_value), 0)::int AS modifier_total
            FROM match_turn_effects
            WHERE match_id = ?
              AND expires_turn >= ?
              AND stat_type = 'DAMAGE_MODIFIER'
              AND affected_user_id IN (%s)
            GROUP BY affected_user_id
            """.formatted(placeholders),
            params.toArray()
        );
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> modifiers = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long affectedUserId = toLong(row.get("affected_user_id"));
            if (affectedUserId == null || affectedUserId <= 0) {
                continue;
            }
            modifiers.put(affectedUserId, toInt(row.get("modifier_total")));
        }
        return modifiers;
    }

    /**
     * 安全轉 Long，支援 number 與 numeric string。
     */
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

    /**
     * 通用轉字串，null 則回 null。
     */
    private String toStringValue(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    /**
     * 通用轉 boolean，支援 Boolean/Number/String。
     */
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

    /**
     * 解析 SQL array 或單值為 Long list，失敗時回退 fallbackSingle。
     */
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

    /**
     * 解析 payload JSON 字串，失敗時回傳帶 raw 的容錯節點。
     */
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

    /**
     * 通用轉 LocalDateTime，支援 LocalDateTime 與 Timestamp。
     */
    private java.time.LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.time.LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    /**
     * 解析藝能基礎攻擊值（value/rawHeader/rawText 依序嘗試）。
     */
    private int resolveArtDamage(String effectJsonText) {
        if (!StringUtils.hasText(effectJsonText)) {
            return 0;
        }
        JsonNode node = parsePayloadJson(effectJsonText);
        Integer direct = toNullableInt(node.path("value").asText(null));
        if (direct != null && direct > 0) {
            return direct;
        }
        String rawHeader = toStringValue(node.path("rawHeader").asText(null));
        int headerDamage = extractDamageFromText(rawHeader);
        if (headerDamage > 0) {
            return headerDamage;
        }
        String rawText = toStringValue(node.path("rawText").asText(null));
        return extractDamageFromText(rawText);
    }

    /**
     * 從文字中擷取傷害值（特殊/一般/最後數字回退）。
     */
    private int extractDamageFromText(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        Matcher special = SPECIAL_DAMAGE_PATTERN.matcher(text);
        if (special.find()) {
            Integer parsed = toNullableInt(special.group(1));
            return parsed == null ? 0 : parsed;
        }
        Matcher normal = DAMAGE_PATTERN.matcher(text);
        if (normal.find()) {
            Integer parsed = toNullableInt(normal.group(1));
            return parsed == null ? 0 : parsed;
        }
        // 卡面原文常見格式為「藝能名稱 ... 40」或「100+」，此處以最後一個正整數作為基礎攻擊值回退。
        Matcher anyNumber = ANY_NUMBER_PATTERN.matcher(text);
        int fallback = 0;
        while (anyNumber.find()) {
            Integer parsed = toNullableInt(anyNumber.group(1));
            if (parsed != null && parsed > 0) {
                fallback = parsed;
            }
        }
        if (fallback > 0) {
            return fallback;
        }
        return 0;
    }
}
