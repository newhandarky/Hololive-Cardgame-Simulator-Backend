package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 回合 Cheer 的 source 與 target 唯讀查詢唯一入口。
 */
@Service
public class TurnCheerAvailabilityService {

    private final JdbcTemplate jdbcTemplate;

    public TurnCheerAvailabilityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TurnCheerAvailability> findAvailability(Long matchId, Long userId) {
        if (matchId == null || userId == null || matchId <= 0 || userId <= 0) {
            return Optional.empty();
        }

        TurnCheerSource source = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   mc.zone
            FROM match_cards mc
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'CHEER_DECK'
              AND EXISTS (
                  SELECT 1
                  FROM cheer_cards cc
                  WHERE cc.card_id = mc.card_id
              )
            ORDER BY mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> rs.next()
                ? new TurnCheerSource(
                    rs.getLong("card_instance_id"),
                    rs.getString("card_id"),
                    rs.getString("zone")
                )
                : null,
            matchId,
            userId
        );
        if (source == null) {
            return Optional.empty();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
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
        List<TurnCheerTarget> targets = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long cardInstanceId = MatchEffectValueHelper.asLong(row.get("card_instance_id"));
            if (cardInstanceId == null || cardInstanceId <= 0) {
                continue;
            }
            targets.add(new TurnCheerTarget(
                cardInstanceId,
                MatchEffectValueHelper.asText(row.get("card_id")),
                MatchEffectValueHelper.asText(row.get("name")),
                MatchEffectValueHelper.asText(row.get("card_type")),
                MatchEffectValueHelper.asText(row.get("level_type")),
                MatchEffectValueHelper.asText(row.get("zone")),
                MatchEffectValueHelper.asText(row.get("image_url"))
            ));
        }
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TurnCheerAvailability(
            source.cardInstanceId(),
            source.cardId(),
            source.zone(),
            targets
        ));
    }

    private record TurnCheerSource(long cardInstanceId, String cardId, String zone) {
    }

    public record TurnCheerAvailability(
        long sourceCardInstanceId,
        String sourceCardId,
        String sourceZone,
        List<TurnCheerTarget> targets
    ) {
        public TurnCheerAvailability {
            targets = List.copyOf(targets);
        }
    }

    public record TurnCheerTarget(
        long cardInstanceId,
        String cardId,
        String name,
        String cardType,
        String levelType,
        String zone,
        String imageUrl
    ) {
    }
}
