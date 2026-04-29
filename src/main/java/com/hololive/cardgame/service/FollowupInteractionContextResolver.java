package com.hololive.cardgame.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class FollowupInteractionContextResolver {

    private final FollowupInteractionContextBuilder followupInteractionContextBuilder;
    private final FollowupCardCandidateLoader followupCardCandidateLoader;

    FollowupInteractionContextResolver(JdbcTemplate jdbcTemplate) {
        this.followupInteractionContextBuilder = new FollowupInteractionContextBuilder();
        this.followupCardCandidateLoader = new FollowupCardCandidateLoader(jdbcTemplate);
    }

    FollowupInteractionContext resolve(
        Long matchId,
        Long userId,
        Map<String, Object> effectSummary
    ) {
        return followupInteractionContextBuilder.buildFollowupInteractionContext(
            userId,
            effectSummary,
            (viewerUserId, ownerUserId, cardInstanceId, fallbackZone, fallbackCardId) ->
                followupCardCandidateLoader.loadCardCandidateForDecision(
                    matchId,
                    viewerUserId,
                    ownerUserId,
                    cardInstanceId,
                    fallbackZone,
                    fallbackCardId
                )
        );
    }
}
