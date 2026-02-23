package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.AttachCheerActionRequest;
import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.PlaySupportActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MatchActionService {

    private static final Set<String> PLAY_TO_STAGE_ZONES = Set.of("CENTER", "BACK");
    private static final Set<String> CHEER_SOURCE_ZONES = Set.of("HAND", "CHEER_DECK");

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final MatchActionRepository matchActionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MatchEffectService matchEffectService;
    private final MatchEventHookService matchEventHookService;

    public MatchActionService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        MatchActionRepository matchActionRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchEffectService matchEffectService,
        MatchEventHookService matchEventHookService
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.matchActionRepository = matchActionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.matchEffectService = matchEffectService;
        this.matchEventHookService = matchEventHookService;
    }

    @Transactional
    public void playToStage(Long matchId, Long userId, PlayToStageActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        Long cardInstanceId = requirePositiveId(request == null ? null : request.getCardInstanceId(), "cardInstanceId");
        String targetZone = normalizeZone(request == null ? null : request.getTargetZone());
        if (!PLAY_TO_STAGE_ZONES.contains(targetZone)) {
            throw new IllegalArgumentException("targetZone 只支援 CENTER 或 BACK");
        }

        Map<String, Object> card = loadOwnedCardInstance(matchId, userId, cardInstanceId);
        String sourceZone = normalizeZone(card.get("zone"));
        if (!"HAND".equals(sourceZone)) {
            throw new IllegalStateException("只能從手牌打出 Holomen");
        }

        String cardId = asString(card.get("card_id"));
        String levelType = jdbcTemplate.query(
            "SELECT level_type FROM member_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getString("level_type") : null,
            cardId
        );
        if (!StringUtils.hasText(levelType)) {
            throw new IllegalStateException("只有 MEMBER 卡可以打到場上");
        }

        int occupiedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE match_id = ? AND owner_user_id = ? AND zone = ?",
            Integer.class,
            matchId,
            userId,
            targetZone
        );
        if ("CENTER".equals(targetZone) && occupiedCount > 0) {
            throw new IllegalStateException("CENTER 已有 Holomen");
        }
        if ("BACK".equals(targetZone) && occupiedCount >= 5) {
            throw new IllegalStateException("BACK 已滿（最多 5 張）");
        }

        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            cardInstanceId,
            matchId,
            userId
        );
        if (updated != 1) {
            throw new IllegalStateException("打牌失敗，請重新整理對戰狀態");
        }

        jdbcTemplate.update(
            """
            INSERT INTO match_holomems (
                match_id, owner_user_id, match_card_id, card_id, zone, is_rested, is_face_down, damage_taken, current_level
            ) VALUES (?, ?, ?, ?, ?, FALSE, FALSE, 0, ?)
            """,
            matchId,
            userId,
            cardInstanceId,
            cardId,
            targetZone,
            normalizeLevel(levelType)
        );

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> triggerSummary = matchEventHookService.onHolomemEnter(
            matchId,
            userId,
            cardId,
            cardInstanceId,
            targetZone
        );

        appendAction(
            context.match,
            userId,
            "PLAY_TO_STAGE",
            toJson(
                Map.of(
                    "cardInstanceId", cardInstanceId,
                    "cardId", cardId,
                    "targetZone", targetZone,
                    "triggerSummary", triggerSummary
                )
            ),
            context.turnNumber
        );
    }

    @Transactional
    public void playSupport(Long matchId, Long userId, PlaySupportActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        Long cardInstanceId = requirePositiveId(request == null ? null : request.getCardInstanceId(), "cardInstanceId");
        Long targetHolomemCardInstanceId = request == null ? null : request.getTargetHolomemCardInstanceId();
        List<Long> selectedCardInstanceIds = request == null ? null : request.getSelectedCardInstanceIds();

        Map<String, Object> card = loadOwnedCardInstance(matchId, userId, cardInstanceId);
        String sourceZone = normalizeZone(card.get("zone"));
        if (!"HAND".equals(sourceZone)) {
            throw new IllegalStateException("SUPPORT 只能從手牌使用");
        }

        String cardId = asString(card.get("card_id"));
        String cardType = jdbcTemplate.query(
            "SELECT card_type FROM cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getString("card_type") : null,
            cardId
        );
        if (!"SUPPORT".equals(normalizeZone(cardType))) {
            throw new IllegalStateException("指定卡片不是 SUPPORT");
        }

        Map<String, Object> supportRow = jdbcTemplate.query(
            """
            SELECT effect_type, effect_json::text AS effect_json_text, target_type
            FROM support_cards
            WHERE card_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("effect_type", rs.getString("effect_type"));
                row.put("effect_json_text", rs.getString("effect_json_text"));
                row.put("target_type", rs.getString("target_type"));
                return row;
            },
            cardId
        );
        if (supportRow == null) {
            throw new IllegalStateException("找不到 SUPPORT 效果定義");
        }

        int nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            userId
        );
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = ?,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            nextArchiveOrder,
            cardInstanceId,
            matchId,
            userId
        );
        if (updated != 1) {
            throw new IllegalStateException("使用 SUPPORT 失敗，請重新整理對戰狀態");
        }

        Map<String, Object> effectSummary = matchEffectService.applySupportEffect(
            matchId,
            userId,
            asString(supportRow.get("effect_type")),
            asString(supportRow.get("effect_json_text")),
            asString(supportRow.get("target_type")),
            selectedCardInstanceIds,
            targetHolomemCardInstanceId
        );

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", cardInstanceId);
        payload.put("cardId", cardId);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        payload.put("effect", effectSummary);
        appendAction(
            context.match,
            userId,
            "PLAY_SUPPORT",
            toJson(payload),
            context.turnNumber
        );
    }

    @Transactional
    public void attachCheer(Long matchId, Long userId, AttachCheerActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        Long cheerCardInstanceId = requirePositiveId(
            request == null ? null : request.getCheerCardInstanceId(),
            "cheerCardInstanceId"
        );
        Long targetHolomemCardInstanceId = requirePositiveId(
            request == null ? null : request.getTargetHolomemCardInstanceId(),
            "targetHolomemCardInstanceId"
        );

        Long matchHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            targetHolomemCardInstanceId
        );
        if (matchHolomemId == null) {
            throw new IllegalArgumentException("找不到要附加 Cheer 的 Holomen");
        }

        Map<String, Object> cheerCard = loadOwnedCardInstance(matchId, userId, cheerCardInstanceId);
        String sourceZone = normalizeZone(cheerCard.get("zone"));
        if (!CHEER_SOURCE_ZONES.contains(sourceZone)) {
            throw new IllegalStateException("Cheer 只能從 HAND 或 CHEER_DECK 附加");
        }
        String cheerCardId = asString(cheerCard.get("card_id"));
        Integer cheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cheer_cards WHERE card_id = ?",
            Integer.class,
            cheerCardId
        );
        if (cheerCount == null || cheerCount == 0) {
            throw new IllegalStateException("指定卡片不是 Cheer 卡");
        }

        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone IN ('HAND','CHEER_DECK')
            """,
            cheerCardInstanceId,
            matchId,
            userId
        );
        if (updated != 1) {
            throw new IllegalStateException("附加 Cheer 失敗，請重新整理對戰狀態");
        }

        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
            VALUES (?, ?, FALSE)
            """,
            matchHolomemId,
            cheerCardId
        );

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        appendAction(
            context.match,
            userId,
            "ATTACH_CHEER",
            toJson(
                Map.of(
                    "cheerCardInstanceId", cheerCardInstanceId,
                    "cheerCardId", cheerCardId,
                    "targetHolomemCardInstanceId", targetHolomemCardInstanceId
                )
            ),
            context.turnNumber
        );
    }

    @Transactional
    public void attackArt(Long matchId, Long userId, AttackArtActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN, MatchPhase.PERFORMANCE));
        Long attackerCardInstanceId = requirePositiveId(
            request == null ? null : request.getAttackerCardInstanceId(),
            "attackerCardInstanceId"
        );
        Long targetCardInstanceId = request == null ? null : request.getTargetCardInstanceId();

        String attackerZone = jdbcTemplate.query(
            """
            SELECT h.zone
            FROM match_holomems h
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            """,
            rs -> rs.next() ? rs.getString("zone") : null,
            matchId,
            userId,
            attackerCardInstanceId
        );
        if (!StringUtils.hasText(attackerZone)) {
            throw new IllegalArgumentException("找不到攻擊中的 Holomen");
        }
        if (!"CENTER".equals(normalizeZone(attackerZone))) {
            throw new IllegalStateException("目前僅支援由 CENTER 發動攻擊");
        }

        Long lifeCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'LIFE'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            context.opponentUserId
        );
        if (lifeCardInstanceId == null) {
            throw new IllegalStateException("對手沒有可失去的 LIFE");
        }

        int nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            context.opponentUserId
        );

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = ?,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'LIFE'
            """,
            nextArchiveOrder,
            lifeCardInstanceId,
            matchId,
            context.opponentUserId
        );

        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = GREATEST(COALESCE(current_life, 0) - 1, 0),
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            context.opponentUserId
        );

        context.match.setCurrentPhase(MatchPhase.END.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attackerCardInstanceId", attackerCardInstanceId);
        payload.put("targetCardInstanceId", targetCardInstanceId);
        payload.put("lostLifeCardInstanceId", lifeCardInstanceId);

        appendAction(
            context.match,
            userId,
            "ATTACK_ART",
            toJson(payload),
            context.turnNumber
        );
    }

    @Transactional
    public void endTurn(Long matchId, Long userId) {
        ActionContext context = loadActionContext(
            matchId,
            userId,
            Set.of(MatchPhase.MAIN, MatchPhase.PERFORMANCE, MatchPhase.END)
        );
        int clearedEffectCount = matchEffectService.clearExpiredTurnEffects(matchId, context.turnNumber);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", userId);
        payload.put("toUserId", context.opponentUserId);
        payload.put("clearedExpiredTurnEffects", clearedEffectCount);

        appendAction(
            context.match,
            userId,
            "END_TURN",
            toJson(payload),
            context.turnNumber
        );

        context.match.setCurrentTurnPlayerId(context.opponentUserId);
        context.match.setTurnNumber(context.turnNumber + 1);
        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);
    }

    private ActionContext loadActionContext(Long matchId, Long userId, Set<MatchPhase> allowedPhases) {
        MatchEntity match = matchRepository.findByIdForUpdate(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));

        if (!LobbyMatchStatus.STARTED.name().equals(match.getLobbyStatus())) {
            throw new IllegalStateException("對戰尚未開始");
        }
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalArgumentException("你不在此房間中");
        }
        if (match.getCurrentTurnPlayerId() == null || !match.getCurrentTurnPlayerId().equals(userId)) {
            throw new IllegalStateException("現在不是你的回合");
        }

        MatchPhase phase = parsePhase(match.getCurrentPhase());
        if (!allowedPhases.contains(phase)) {
            throw new IllegalStateException("目前 phase=" + phase + "，無法執行此操作");
        }

        Long opponentUserId = resolveOpponent(match, userId);
        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        return new ActionContext(match, phase, turnNumber, opponentUserId);
    }

    private Map<String, Object> loadOwnedCardInstance(Long matchId, Long userId, Long cardInstanceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT id, card_id, zone
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            cardInstanceId,
            matchId,
            userId
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到指定卡片實例");
        }
        return rows.get(0);
    }

    private Long resolveOpponent(MatchEntity match, Long userId) {
        if (match.getPlayerAId() != null && !match.getPlayerAId().equals(userId)) {
            return match.getPlayerAId();
        }
        if (match.getPlayerBId() != null && !match.getPlayerBId().equals(userId)) {
            return match.getPlayerBId();
        }
        throw new IllegalStateException("找不到對手玩家");
    }

    private MatchPhase parsePhase(String text) {
        if (!StringUtils.hasText(text)) {
            return MatchPhase.RESET;
        }
        try {
            return MatchPhase.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MatchPhase.RESET;
        }
    }

    private Long requirePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " 不可為空");
        }
        return value;
    }

    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeLevel(String levelType) {
        String normalized = normalizeZone(levelType);
        if ("DEBUT".equals(normalized) || "FIRST".equals(normalized) || "SECOND".equals(normalized)) {
            return normalized;
        }
        return "DEBUT";
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
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

    private void touchUpdatedAt(MatchEntity match) {
        match.setUpdatedAt(LocalDateTime.now());
    }

    private record ActionContext(
        MatchEntity match,
        MatchPhase phase,
        int turnNumber,
        Long opponentUserId
    ) {
    }
}
