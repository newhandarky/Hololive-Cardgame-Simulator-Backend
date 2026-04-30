package com.hololive.cardgame.service;

import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchRepository;
import org.springframework.jdbc.core.JdbcTemplate;

class AttackPerformanceStateUpdater {

    private final JdbcTemplate jdbcTemplate;
    private final AttackPerformanceAvailabilityService attackPerformanceAvailabilityService;
    private final MatchTimestampService matchTimestampService;
    private final MatchRepository matchRepository;

    AttackPerformanceStateUpdater(
        JdbcTemplate jdbcTemplate,
        AttackPerformanceAvailabilityService attackPerformanceAvailabilityService,
        MatchTimestampService matchTimestampService,
        MatchRepository matchRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.attackPerformanceAvailabilityService = attackPerformanceAvailabilityService;
        this.matchTimestampService = matchTimestampService;
        this.matchRepository = matchRepository;
    }

    boolean restAttackerAndSavePerformancePhase(AttackArtApplicationContext context) {
        int attackerRested = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND is_rested = FALSE
            """,
            context.attackerHolomemId(),
            context.matchId(),
            context.attackerUserId()
        );
        if (attackerRested != 1) {
            throw new IllegalStateException("藝能結算失敗，請重新整理後再試");
        }

        boolean hasNextPerformanceAction = attackPerformanceAvailabilityService.hasAvailableArtAttacker(
            context.matchId(),
            context.attackerUserId(),
            context.turnNumber()
        );
        context.match().setCurrentPhase(MatchPhase.PERFORMANCE.name());
        matchTimestampService.touchUpdatedAt(context.match());
        matchRepository.saveAndFlush(context.match());
        return hasNextPerformanceAction;
    }
}
