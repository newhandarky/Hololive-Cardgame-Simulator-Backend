package com.hololive.cardgame.service;

import static com.hololive.cardgame.service.MatchEffectValueHelper.asLong;
import static com.hololive.cardgame.service.MatchEffectValueHelper.asText;
import static com.hololive.cardgame.service.MatchEffectValueHelper.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchLookEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;

    MatchLookEffectExecutionService(JdbcTemplate jdbcTemplate, EffectTextParser effectTextParser) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
    }

    /**
     * 執行查看牌庫頂效果，產生 pending decision 所需資料。
     */
    Map<String, Object> executeLookTopDeckEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (rawText.contains("マスコットが付いている")) {
            Integer mascotAttachedCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomem_supports hs
                JOIN match_holomems h ON h.id = hs.match_holomem_id
                JOIN support_cards sc ON sc.card_id = hs.support_card_id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                  AND hs.support_type = 'MASCOT'
                """,
                Integer.class,
                matchId,
                userId
            );
            if (mascotAttachedCount == null || mascotAttachedCount <= 0) {
                return executeNoOpEffect(effectType, effectNode, "條件不成立：沒有附加中的マスコット");
            }
        }

        Map<String, Object> topCard = jdbcTemplate.query(
            """
            SELECT id, card_id, order_index
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("order_index", rs.getObject("order_index"));
                return row;
            },
            matchId,
            userId
        );
        if (topCard == null) {
            return executeNoOpEffect(effectType, effectNode, "牌庫沒有可查看的卡片");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("lookedCardInstanceId", asLong(topCard.get("id")));
        summary.put("lookedCardId", asText(topCard.get("card_id")));
        summary.put("reordered", false);
        summary.put("reason", "目前預設維持牌庫頂部順序");
        return summary;
    }

    /**
     * 執行查看對手手牌效果（只回傳可公開資訊）。
     */
    Map<String, Object> executeLookOpponentHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        if (opponentUserId == null || opponentUserId <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到可查看手牌的對手");
        }
        List<Map<String, Object>> lookedCards = loadCardsForLookDecision(matchId, opponentUserId, "HAND");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("lookedUserId", opponentUserId);
        summary.put("lookedZone", "HAND");
        summary.put("lookedCardCount", lookedCards.size());
        summary.put("lookedCards", lookedCards);
        return summary;
    }

    /**
     * 執行查看 Holopower 區效果。
     */
    Map<String, Object> executeLookHolopowerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        boolean lookOpponent = rawText.contains("相手");
        Long lookedUserId = lookOpponent ? resolveOpponentUserId(matchId, userId) : userId;
        if (lookedUserId == null || lookedUserId <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到可查看 HOLOPOWER 的玩家");
        }
        List<Map<String, Object>> lookedCards = loadCardsForLookDecision(matchId, lookedUserId, "HOLOPOWER");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("lookedUserId", lookedUserId);
        summary.put("lookedZone", "HOLOPOWER");
        summary.put("lookedCardCount", lookedCards.size());
        summary.put("lookedCards", lookedCards);
        return summary;
    }

    private List<Map<String, Object>> loadCardsForLookDecision(Long matchId, Long ownerUserId, String zone) {
        return jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   mc.zone,
                   c.name,
                   c.card_type,
                   c.image_url,
                   m.level_type
            FROM match_cards mc
            LEFT JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = ?
            ORDER BY mc.order_index NULLS LAST, mc.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cardInstanceId", rs.getLong("card_instance_id"));
                row.put("cardId", rs.getString("card_id"));
                row.put("zone", normalize(rs.getString("zone")));
                row.put("name", rs.getString("name"));
                row.put("cardType", rs.getString("card_type"));
                row.put("imageUrl", rs.getString("image_url"));
                row.put("levelType", rs.getString("level_type"));
                return row;
            },
            matchId,
            ownerUserId,
            zone
        );
    }

    private Long resolveOpponentUserId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT player_a_id, player_b_id
            FROM matches
            WHERE id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Long a = asLong(rs.getObject("player_a_id"));
                Long b = asLong(rs.getObject("player_b_id"));
                if (a != null && !a.equals(userId)) {
                    return a;
                }
                if (b != null && !b.equals(userId)) {
                    return b;
                }
                return null;
            },
            matchId
        );
    }

    private Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        summary.put("rawText", effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        return summary;
    }
}
