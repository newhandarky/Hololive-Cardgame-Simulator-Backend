package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SendTurnCheerApplicationServiceTest {

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchPlayerRepository matchPlayerRepository;
    @Mock
    private TurnActionRuleService turnActionRuleService;
    @Mock
    private TurnCheerAvailabilityService turnCheerAvailabilityService;
    @Mock
    private MatchTurnLifecycleService matchTurnLifecycleService;

    @InjectMocks
    private SendTurnCheerApplicationService service;

    private MatchEntity match;

    @BeforeEach
    void setUpValidSharedFacts() {
        match = new MatchEntity();
        match.setId(101L);
        match.setTurnNumber(3);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        when(matchRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(match));
        when(turnActionRuleService.isMatchActive(match)).thenReturn(true);
        when(turnActionRuleService.isMatchStarted(match)).thenReturn(true);
        when(matchPlayerRepository.existsByMatchIdAndUserId(101L, 202L)).thenReturn(true);
    }

    @Test
    void executeShouldCreatePendingInteractionAndBeginCheerPhase() {
        stubCurrentMainPhase();
        TurnCheerAvailabilityService.TurnCheerAvailability availability = availability();
        when(turnCheerAvailabilityService.findAvailability(101L, 202L)).thenReturn(Optional.of(availability));
        when(matchTurnLifecycleService.createTurnSendCheerPendingInteraction(101L, 202L, availability))
            .thenReturn(777L);

        service.execute(101L, 202L);

        verify(matchTurnLifecycleService).beginTurnCheer(match, 202L, 3, 777L);
    }

    @Test
    void executeShouldRejectBlockingPendingInteraction() {
        stubCurrentMainPhase();
        when(turnActionRuleService.hasBlockingPendingDecision(101L, 202L)).thenReturn(true);

        assertThatThrownBy(() -> service.execute(101L, 202L))
            .isInstanceOf(GameRuleException.class)
            .satisfies(error -> assertThat(((GameRuleException) error).getCode())
                .isEqualTo(GameErrorCode.PENDING_INTERACTION_BLOCKED));
    }

    @Test
    void executeShouldRejectDuplicateTurnCheer() {
        stubCurrentMainPhase();
        when(turnActionRuleService.hasTurnCheerAction(101L, 202L, 3)).thenReturn(true);

        assertThatThrownBy(() -> service.execute(101L, 202L))
            .isInstanceOf(GameRuleException.class)
            .satisfies(error -> assertThat(((GameRuleException) error).getCode())
                .isEqualTo(GameErrorCode.TURN_CHEER_ALREADY_USED));
    }

    @Test
    void executeShouldRejectUnavailableSourceOrTarget() {
        stubCurrentMainPhase();
        when(turnCheerAvailabilityService.findAvailability(101L, 202L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(101L, 202L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("目前無法發送吶喊");
    }

    private void stubCurrentMainPhase() {
        when(turnActionRuleService.isCurrentTurnPlayer(match, 202L)).thenReturn(true);
        when(turnActionRuleService.parsePhase(MatchPhase.MAIN.name())).thenReturn(MatchPhase.MAIN);
    }

    private TurnCheerAvailabilityService.TurnCheerAvailability availability() {
        return new TurnCheerAvailabilityService.TurnCheerAvailability(
            500L,
            "hY01-001",
            "CHEER_DECK",
            List.of(new TurnCheerAvailabilityService.TurnCheerTarget(
                900L,
                "hBP01-001",
                "Tokino Sora",
                "HOLOMEM",
                "DEBUT",
                "CENTER",
                "center.png"
            ))
        );
    }
}
