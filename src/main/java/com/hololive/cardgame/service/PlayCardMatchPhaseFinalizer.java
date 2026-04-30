package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchRepository;

class PlayCardMatchPhaseFinalizer {

    private final MatchTimestampService matchTimestampService;
    private final MatchRepository matchRepository;

    PlayCardMatchPhaseFinalizer(
        MatchTimestampService matchTimestampService,
        MatchRepository matchRepository
    ) {
        this.matchTimestampService = matchTimestampService;
        this.matchRepository = matchRepository;
    }

    void finalizePhase(PlayCardResolutionResult resolutionResult) {
        MatchEntity match = resolutionResult.match();
        match.setCurrentPhase(resolvePhase(resolutionResult).name());
        matchTimestampService.touchUpdatedAt(match);
        matchRepository.saveAndFlush(match);
    }

    MatchPhase resolvePhase(PlayCardResolutionResult resolutionResult) {
        return resolutionResult.openingReset() ? MatchPhase.RESET : MatchPhase.MAIN;
    }
}
