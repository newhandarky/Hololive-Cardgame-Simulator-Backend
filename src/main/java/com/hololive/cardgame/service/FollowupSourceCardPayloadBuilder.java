package com.hololive.cardgame.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class FollowupSourceCardPayloadBuilder {

    private static final String FALLBACK_ZONE_STAGE = "STAGE";

    private final FollowupCardCandidateLoader followupCardCandidateLoader;

    FollowupSourceCardPayloadBuilder(JdbcTemplate jdbcTemplate) {
        this.followupCardCandidateLoader = new FollowupCardCandidateLoader(jdbcTemplate);
    }

    Map<String, Object> buildOwnedStageCard(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String fallbackCardId
    ) {
        return buildOwnedCard(matchId, userId, cardInstanceId, FALLBACK_ZONE_STAGE, fallbackCardId);
    }

    Map<String, Object> buildOwnedCard(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String fallbackZone,
        String fallbackCardId
    ) {
        return followupCardCandidateLoader.loadOwnedCardCandidateForDecision(
            matchId,
            userId,
            cardInstanceId,
            fallbackZone,
            fallbackCardId
        );
    }
}
