package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchGiftArchiveReturnEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;

    MatchGiftArchiveReturnEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
    }

    /**
     * 執行 HBP02-039 類「已公開且本來要進 Archive 的支援卡改為回手」效果。
     */
    Map<String, Object> executeReplaceArchiveWithHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long holderCardInstanceId
    ) {
        List<Long> archivedSupportCardInstanceIds = loadLatestHoloxArchivedSupportCardInstanceIds(
            matchId,
            userId,
            holderCardInstanceId
        );
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("candidateCardInstanceIds", archivedSupportCardInstanceIds);
        if (archivedSupportCardInstanceIds.isEmpty()) {
            summary.put("applied", false);
            summary.put("reason", "本次公開沒有支援卡可改為回手");
            return summary;
        }

        Long movedCardInstanceId = null;
        String movedCardId = null;
        int nextHandOrder = nextZoneOrder(matchId, userId, "HAND");
        for (Long candidateCardInstanceId : archivedSupportCardInstanceIds) {
            if (candidateCardInstanceId == null || candidateCardInstanceId <= 0) {
                continue;
            }
            int updated = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'HAND',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'ARCHIVE'
                """,
                nextHandOrder++,
                candidateCardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            movedCardInstanceId = candidateCardInstanceId;
            movedCardId = jdbcTemplate.query(
                "SELECT card_id FROM match_cards WHERE id = ?",
                rs -> rs.next() ? rs.getString("card_id") : null,
                candidateCardInstanceId
            );
            break;
        }

        if (movedCardInstanceId == null) {
            summary.put("applied", false);
            summary.put("reason", "找不到可從 Archive 改為回手的支援卡");
            return summary;
        }

        summary.put("applied", true);
        summary.put("movedCardInstanceId", movedCardInstanceId);
        summary.put("movedCardId", movedCardId);
        summary.put("movedCount", 1);
        return summary;
    }

    private List<Long> loadLatestHoloxArchivedSupportCardInstanceIds(
        Long matchId,
        Long userId,
        Long holderCardInstanceId
    ) {
        if (matchId == null || userId == null || holderCardInstanceId == null || holderCardInstanceId <= 0) {
            return List.of();
        }
        String payloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'ATTACK_ART'
              AND payload ->> 'attackerCardInstanceId' = ?
              AND payload ->> 'artName' = 'ホロックスロット'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : null,
            matchId,
            userId,
            holderCardInstanceId.toString()
        );
        JsonNode payloadNode = effectTextParser.parseEffectJson(payloadText);
        if (payloadNode == null || payloadNode.isNull()) {
            return List.of();
        }
        JsonNode holoxRevealNode = payloadNode.get("holoxReveal");
        if (holoxRevealNode == null || holoxRevealNode.isNull()) {
            return List.of();
        }
        return MatchEffectValueHelper.extractEffectNodeLongList(holoxRevealNode, "archivedSupportCardInstanceIds");
    }

    private int nextZoneOrder(Long matchId, Long userId, String zone) {
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        return nextOrder == null ? 1 : nextOrder;
    }
}
