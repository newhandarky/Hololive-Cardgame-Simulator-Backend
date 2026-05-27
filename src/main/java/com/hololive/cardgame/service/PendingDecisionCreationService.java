package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class PendingDecisionCreationService {

    private static final String SUPPORT_DECISION_TYPE_CARD_SELECTION = "CARD_SELECTION";
    private static final String INTERACTION_TYPE_DRAW_REVEAL = "DRAW_REVEAL";
    private static final String INTERACTION_TYPE_LIVE_START = "LIVE_START";
    private static final String INTERACTION_TYPE_SEND_CHEER = "SEND_CHEER";
    private static final String INTERACTION_TYPE_TURN_START = "TURN_START";

    private final JdbcTemplate jdbcTemplate;
    private final MatchPayloadJsonService matchPayloadJsonService;
    private final PendingDecisionReader pendingDecisionReader;

    PendingDecisionCreationService(
        JdbcTemplate jdbcTemplate,
        MatchPayloadJsonService matchPayloadJsonService,
        PendingDecisionReader pendingDecisionReader
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchPayloadJsonService = matchPayloadJsonService;
        this.pendingDecisionReader = pendingDecisionReader;
    }

    Long createTurnStartPendingInteraction(Long matchId, Long userId, int turnNumber) {
        if (matchId == null || userId == null || userId <= 0) {
            return null;
        }
        if (pendingDecisionReader.hasAnyPendingDecision(matchId, userId)) {
            return null;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_TURN_START);
        context.put("title", "回合開始");
        context.put("message", "現在是你的回合。請先確認，接著依序進入抽牌與吶喊階段。");
        context.put("turnNumber", turnNumber);

        return createSimpleInteraction(
            matchId,
            userId,
            INTERACTION_TYPE_TURN_START,
            turnNumber,
            context
        );
    }

    Long createLiveStartPendingInteraction(Long matchId, Long userId, int turnNumber) {
        if (matchId == null || userId == null || userId <= 0) {
            return null;
        }
        if (pendingDecisionReader.hasAnyPendingDecision(matchId, userId)) {
            return null;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_LIVE_START);
        context.put("title", "LIVE START!!");
        context.put("message", "雙方開場舞台已設置完成，確認後翻開 CENTER 與 BACK 並開始對戰。");
        context.put("turnNumber", turnNumber);

        return createSimpleInteraction(
            matchId,
            userId,
            INTERACTION_TYPE_LIVE_START,
            turnNumber,
            context
        );
    }

    Long createDrawRevealPendingInteraction(Long matchId, Long userId, Long drawnCardInstanceId) {
        if (drawnCardInstanceId == null || drawnCardInstanceId <= 0) {
            return null;
        }
        if (pendingDecisionReader.hasAnyPendingDecision(matchId, userId)) {
            return null;
        }

        Map<String, Object> drawnCard = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   c.name,
                   c.card_type,
                   c.image_url
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cardInstanceId", rs.getLong("card_instance_id"));
                row.put("cardId", rs.getString("card_id"));
                row.put("name", rs.getString("name"));
                row.put("cardType", rs.getString("card_type"));
                row.put("zone", "HAND");
                row.put("imageUrl", rs.getString("image_url"));
                return row;
            },
            matchId,
            userId,
            drawnCardInstanceId
        );
        if (drawnCard == null) {
            return null;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_DRAW_REVEAL);
        context.put("title", "回合抽牌");
        context.put("message", "你抽到 1 張牌，確認後可繼續操作。");
        context.put("cards", List.of(drawnCard));

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
            ) VALUES (?, ?, ?, 'DRAW_TURN', ?, ?, ?, 1, 1, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_DRAW_REVEAL,
            drawnCardInstanceId,
            MatchEffectValueHelper.asText(drawnCard.get("cardId")),
            INTERACTION_TYPE_DRAW_REVEAL,
            PendingDecisionReader.PENDING_STATUS,
            toJson(context)
        );
    }

    Long createTurnSendCheerPendingInteraction(Long matchId, Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        Long cheerCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (cheerCardInstanceId == null) {
            return null;
        }
        return createSendCheerPendingInteraction(
            matchId,
            userId,
            cheerCardInstanceId,
            "TURN_CHEER",
            "回合吶喊",
            "請從エール牌庫發送 1 張吶喊到我方 Holomem。"
        );
    }

    Long createSendCheerPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceActionType,
        String title,
        String message
    ) {
        if (sourceCardInstanceId == null || sourceCardInstanceId <= 0 || userId == null || userId <= 0) {
            return null;
        }
        Map<String, Object> sourceCard = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   mc.zone,
                   c.name,
                   c.card_type,
                   c.image_url
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cardInstanceId", rs.getLong("card_instance_id"));
                row.put("cardId", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                row.put("name", rs.getString("name"));
                row.put("cardType", rs.getString("card_type"));
                row.put("imageUrl", rs.getString("image_url"));
                return row;
            },
            matchId,
            userId,
            sourceCardInstanceId
        );
        if (sourceCard == null) {
            return null;
        }
        String sourceCardId = MatchEffectValueHelper.asText(sourceCard.get("cardId"));
        Integer cheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cheer_cards WHERE card_id = ?",
            Integer.class,
            sourceCardId
        );
        if (cheerCount == null || cheerCount <= 0) {
            return null;
        }
        List<Map<String, Object>> candidateRows = jdbcTemplate.queryForList(
            """
            SELECT h.match_card_id AS card_instance_id,
                   h.card_id,
                   h.zone,
                   c.name,
                   c.card_type,
                   m.level_type,
                   c.image_url
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            LEFT JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER','COLLAB','BACK')
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, h.id
            """,
            matchId,
            userId
        );
        if (candidateRows.isEmpty()) {
            return null;
        }
        List<Long> candidateCardInstanceIds = new ArrayList<>();
        List<Map<String, Object>> candidateCards = new ArrayList<>();
        for (Map<String, Object> row : candidateRows) {
            Long candidateCardInstanceId = MatchEffectValueHelper.asLong(row.get("card_instance_id"));
            if (candidateCardInstanceId == null || candidateCardInstanceId <= 0) {
                continue;
            }
            candidateCardInstanceIds.add(candidateCardInstanceId);
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("cardInstanceId", candidateCardInstanceId);
            candidate.put("cardId", MatchEffectValueHelper.asText(row.get("card_id")));
            candidate.put("name", MatchEffectValueHelper.asText(row.get("name")));
            candidate.put("cardType", MatchEffectValueHelper.asText(row.get("card_type")));
            candidate.put("levelType", MatchEffectValueHelper.asText(row.get("level_type")));
            candidate.put("zone", MatchEffectValueHelper.asText(row.get("zone")));
            candidate.put("imageUrl", MatchEffectValueHelper.asText(row.get("image_url")));
            candidateCards.add(candidate);
        }
        if (candidateCards.isEmpty()) {
            return null;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_SEND_CHEER);
        context.put("title", title);
        context.put("message", message);
        context.put("sourceZone", MatchEffectValueHelper.normalize(sourceCard.get("zone")));
        context.put("cards", candidateCards);
        context.put("candidateCardInstanceIds", candidateCardInstanceIds);

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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_SEND_CHEER,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            INTERACTION_TYPE_SEND_CHEER,
            PendingDecisionReader.PENDING_STATUS,
            toJson(context)
        );
    }

    Long createCardSelectionPendingDecision(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        String effectJson,
        String targetType,
        Long targetHolomemCardInstanceId,
        MatchEffectService.SupportDecisionPlan decisionPlan,
        boolean limited
    ) {
        if (pendingDecisionReader.hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的效果選擇，請先完成決策");
        }
        List<Long> candidateCardInstanceIds = new ArrayList<>();
        List<Map<String, Object>> candidateCards = new ArrayList<>();
        for (MatchEffectService.DecisionCandidate candidate : decisionPlan.candidates()) {
            if (candidate == null || candidate.cardInstanceId() == null) {
                continue;
            }
            candidateCardInstanceIds.add(candidate.cardInstanceId());
            Map<String, Object> candidatePayload = new LinkedHashMap<>();
            candidatePayload.put("cardInstanceId", candidate.cardInstanceId());
            candidatePayload.put("cardId", candidate.cardId());
            candidatePayload.put("name", candidate.name());
            candidatePayload.put("cardType", candidate.cardType());
            candidatePayload.put("levelType", candidate.levelType());
            candidatePayload.put("zone", candidate.zone());
            candidateCards.add(candidatePayload);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("effectType", effectType);
        context.put("effectJson", effectJson);
        context.put("targetType", targetType);
        context.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        context.put("candidateCardInstanceIds", candidateCardInstanceIds);
        context.put("candidateCards", candidateCards);
        context.put("limited", limited);

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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            SUPPORT_DECISION_TYPE_CARD_SELECTION,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            decisionPlan.effectType(),
            decisionPlan.minSelect(),
            decisionPlan.maxSelect(),
            PendingDecisionReader.PENDING_STATUS,
            toJson(context)
        );
    }

    private Long createSimpleInteraction(
        Long matchId,
        Long userId,
        String interactionType,
        int turnNumber,
        Map<String, Object> context
    ) {
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
            interactionType,
            interactionType,
            interactionType,
            PendingDecisionReader.PENDING_STATUS,
            toJson(context)
        );
    }

    private String toJson(Map<String, Object> payload) {
        return matchPayloadJsonService.toJson(payload);
    }
}
