package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DrawTurnApplicationServiceTest {

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchPlayerRepository matchPlayerRepository;
    @Mock
    private TurnActionRuleService turnActionRuleService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private GameActionExecutor gameActionExecutor;
    @Mock
    private MatchTurnLifecycleService matchTurnLifecycleService;

    @InjectMocks
    private DrawTurnApplicationService service;

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
    void executeShouldRejectNonCurrentPlayerWithSharedReasonCode() {
        when(turnActionRuleService.isCurrentTurnPlayer(match, 202L)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(101L, 202L))
            .isInstanceOf(GameRuleException.class)
            .satisfies(error -> assertThat(((GameRuleException) error).getCode()).isEqualTo(GameErrorCode.NOT_YOUR_TURN));
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
    void executeShouldRejectDuplicateTurnDraw() {
        stubCurrentMainPhase();
        when(turnActionRuleService.hasDrawTurnAction(101L, 202L, 3)).thenReturn(true);

        assertThatThrownBy(() -> service.execute(101L, 202L))
            .isInstanceOf(GameRuleException.class)
            .satisfies(error -> assertThat(((GameRuleException) error).getCode())
                .isEqualTo(GameErrorCode.TURN_DRAW_ALREADY_USED));
    }

    private void stubCurrentMainPhase() {
        when(turnActionRuleService.isCurrentTurnPlayer(match, 202L)).thenReturn(true);
        when(turnActionRuleService.parsePhase(MatchPhase.MAIN.name())).thenReturn(MatchPhase.MAIN);
    }
}
