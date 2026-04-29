package com.hololive.cardgame.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class FollowupInteractionPendingDecisionWriter {

    private static final String PENDING_STATUS = "PENDING";

    private final JdbcTemplate jdbcTemplate;
    private final MatchPayloadJsonService matchPayloadJsonService;
    private final FollowupPendingDecisionContextBuilder followupPendingDecisionContextBuilder;

    FollowupInteractionPendingDecisionWriter(
        JdbcTemplate jdbcTemplate,
        MatchPayloadJsonService matchPayloadJsonService,
        FollowupPendingDecisionContextBuilder followupPendingDecisionContextBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchPayloadJsonService = matchPayloadJsonService;
        this.followupPendingDecisionContextBuilder = followupPendingDecisionContextBuilder;
    }

    FollowupInteractionDecision create(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        FollowupInteractionContext interaction
    ) {
        if (interaction == null) {
            return null;
        }
        if (hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的互動，請先完成確認");
        }

        Map<String, Object> context = followupPendingDecisionContextBuilder.buildPendingDecisionContext(
            interaction,
            effectType
        );

        Long decisionId = jdbcTemplate.query(
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
            interaction.decisionType(),
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            interaction.minSelect(),
            interaction.maxSelect(),
            PENDING_STATUS,
            matchPayloadJsonService.toJson(context)
        );
        if (decisionId == null) {
            return null;
        }
        return new FollowupInteractionDecision(decisionId, interaction.decisionType());
    }

    private boolean hasBlockingPendingDecision(Long matchId, Long userId) {
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
}
