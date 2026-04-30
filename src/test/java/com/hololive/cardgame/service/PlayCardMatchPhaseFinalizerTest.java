package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PlayCardMatchPhaseFinalizerTest {

    private final MatchTimestampService matchTimestampService = mock(MatchTimestampService.class);
    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final PlayCardMatchPhaseFinalizer finalizer = new PlayCardMatchPhaseFinalizer(
        matchTimestampService,
        matchRepository
    );

    @Test
    void finalizePhaseShouldKeepResetForOpeningSetup() {
        MatchEntity match = new MatchEntity();
        PlayCardResolutionResult resolutionResult = resolutionResult(match, true);

        finalizer.finalizePhase(resolutionResult);

        assertThat(match.getCurrentPhase()).isEqualTo(MatchPhase.RESET.name());
        InOrder order = inOrder(matchTimestampService, matchRepository);
        order.verify(matchTimestampService).touchUpdatedAt(match);
        order.verify(matchRepository).saveAndFlush(match);
    }

    @Test
    void finalizePhaseShouldSetMainForMainPlacement() {
        MatchEntity match = new MatchEntity();
        PlayCardResolutionResult resolutionResult = resolutionResult(match, false);

        finalizer.finalizePhase(resolutionResult);

        assertThat(match.getCurrentPhase()).isEqualTo(MatchPhase.MAIN.name());
        InOrder order = inOrder(matchTimestampService, matchRepository);
        order.verify(matchTimestampService).touchUpdatedAt(match);
        order.verify(matchRepository).saveAndFlush(match);
    }

    @Test
    void resolvePhaseShouldMirrorOpeningResetFlag() {
        assertThat(finalizer.resolvePhase(resolutionResult(new MatchEntity(), true))).isEqualTo(MatchPhase.RESET);
        assertThat(finalizer.resolvePhase(resolutionResult(new MatchEntity(), false))).isEqualTo(MatchPhase.MAIN);
    }

    private PlayCardResolutionResult resolutionResult(MatchEntity match, boolean openingReset) {
        return new PlayCardResolutionResult(
            match,
            201L,
            3,
            501L,
            "hBP01-001",
            "HAND",
            openingReset ? "CENTER" : "BACK",
            601L,
            3,
            openingReset,
            "DEBUT",
            openingReset
        );
    }
}
