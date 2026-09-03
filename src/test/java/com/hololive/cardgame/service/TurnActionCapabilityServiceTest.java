package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.dto.ActionCapability;
import com.hololive.cardgame.dto.ActionCapabilityCode;
import com.hololive.cardgame.dto.ActionCapabilityReasonCode;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.entity.MatchPlayerEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TurnActionCapabilityServiceTest {

    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final MatchPlayerRepository matchPlayerRepository = mock(MatchPlayerRepository.class);
    private final TurnActionRuleService turnActionRuleService = mock(TurnActionRuleService.class);
    private final TurnActionCapabilityService service = new TurnActionCapabilityService(
        matchRepository,
        matchPlayerRepository,
        turnActionRuleService
    );

    @Test
    void getCapabilitiesShouldReflectCompletedTurnActionsAndPhaseRules() {
        MatchEntity match = activeMainMatch();
        MatchPlayerEntity viewer = viewer();
        stubViewer(match, viewer);
        when(turnActionRuleService.parsePhase(MatchPhase.MAIN.name())).thenReturn(MatchPhase.MAIN);
        when(turnActionRuleService.hasDrawTurnAction(100L, 10L, 2)).thenReturn(true);
        when(turnActionRuleService.hasTurnCheerAction(100L, 10L, 2)).thenReturn(true);
        when(turnActionRuleService.canPerformTurnCheerAction(100L, 10L)).thenReturn(true);

        List<ActionCapability> capabilities = service.getCapabilities(100L, 10L);

        assertThat(capabilities).containsExactly(
            ActionCapability.disabled(
                ActionCapabilityCode.DRAW_TURN,
                ActionCapabilityReasonCode.TURN_DRAW_ALREADY_USED
            ),
            ActionCapability.disabled(
                ActionCapabilityCode.SEND_TURN_CHEER,
                ActionCapabilityReasonCode.TURN_CHEER_ALREADY_USED
            ),
            ActionCapability.enabled(ActionCapabilityCode.ADVANCE_PHASE),
            ActionCapability.disabled(
                ActionCapabilityCode.END_TURN,
                ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED
            )
        );
    }

    @Test
    void getCapabilitiesShouldDisableEveryTurnActionForNonCurrentPlayer() {
        MatchEntity match = activeMainMatch();
        MatchPlayerEntity viewer = viewer();
        stubViewer(match, viewer);
        when(turnActionRuleService.isCurrentTurnPlayer(match, 10L)).thenReturn(false);

        List<ActionCapability> capabilities = service.getCapabilities(100L, 10L);

        assertThat(capabilities).containsExactly(
            ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.NOT_YOUR_TURN),
            ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.NOT_YOUR_TURN),
            ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.NOT_YOUR_TURN),
            ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.NOT_YOUR_TURN)
        );
    }

    @ParameterizedTest
    @MethodSource("phaseCases")
    void getCapabilitiesShouldDescribeEveryPilotPhase(
        MatchPhase phase,
        boolean mulliganDone,
        boolean drawCompleted,
        boolean turnCheerCompleted,
        boolean canSendTurnCheer,
        List<ActionCapability> expected
    ) {
        MatchEntity match = activeMainMatch();
        match.setCurrentPhase(phase.name());
        MatchPlayerEntity viewer = viewer();
        viewer.setMulliganDone(mulliganDone);
        stubViewer(match, viewer);
        when(turnActionRuleService.parsePhase(phase.name())).thenReturn(phase);
        when(turnActionRuleService.hasDrawTurnAction(100L, 10L, 2)).thenReturn(drawCompleted);
        when(turnActionRuleService.hasTurnCheerAction(100L, 10L, 2)).thenReturn(turnCheerCompleted);
        when(turnActionRuleService.canPerformTurnCheerAction(100L, 10L)).thenReturn(canSendTurnCheer);

        assertThat(service.getCapabilities(100L, 10L)).isEqualTo(expected);
    }

    @Test
    void getCapabilitiesShouldDisableEveryTurnActionForFinishedMatch() {
        MatchEntity match = activeMainMatch();
        match.setStatus("finished");
        stubViewer(match, viewer());
        when(turnActionRuleService.isMatchActive(match)).thenReturn(false);

        assertThat(service.getCapabilities(100L, 10L)).containsOnly(
            ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.MATCH_NOT_ACTIVE),
            ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.MATCH_NOT_ACTIVE),
            ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.MATCH_NOT_ACTIVE),
            ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.MATCH_NOT_ACTIVE)
        );
    }

    private static Stream<Arguments> phaseCases() {
        return Stream.of(
            Arguments.of(
                MatchPhase.RESET,
                false,
                false,
                false,
                true,
                List.of(
                    ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.OPENING_SETUP_INCOMPLETE),
                    ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED)
                )
            ),
            Arguments.of(
                MatchPhase.DRAW,
                true,
                false,
                false,
                true,
                List.of(
                    ActionCapability.enabled(ActionCapabilityCode.DRAW_TURN),
                    ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED)
                )
            ),
            Arguments.of(
                MatchPhase.CHEER,
                true,
                true,
                false,
                true,
                List.of(
                    ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.enabled(ActionCapabilityCode.SEND_TURN_CHEER),
                    ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED)
                )
            ),
            Arguments.of(
                MatchPhase.MAIN,
                true,
                true,
                false,
                false,
                List.of(
                    ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.TURN_DRAW_ALREADY_USED),
                    ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.TURN_CHEER_UNAVAILABLE),
                    ActionCapability.enabled(ActionCapabilityCode.ADVANCE_PHASE),
                    ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED)
                )
            ),
            Arguments.of(
                MatchPhase.PERFORMANCE,
                true,
                true,
                true,
                true,
                List.of(
                    ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.enabled(ActionCapabilityCode.ADVANCE_PHASE),
                    ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED)
                )
            ),
            Arguments.of(
                MatchPhase.END,
                true,
                true,
                true,
                true,
                List.of(
                    ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED),
                    ActionCapability.enabled(ActionCapabilityCode.END_TURN)
                )
            )
        );
    }

    private MatchEntity activeMainMatch() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setPlayerAId(10L);
        match.setPlayerBId(20L);
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(2);
        return match;
    }

    private MatchPlayerEntity viewer() {
        MatchPlayerEntity viewer = new MatchPlayerEntity();
        viewer.setMatchId(100L);
        viewer.setUserId(10L);
        viewer.setMulliganDone(true);
        return viewer;
    }

    private void stubViewer(MatchEntity match, MatchPlayerEntity viewer) {
        when(matchRepository.findById(100L)).thenReturn(Optional.of(match));
        when(matchPlayerRepository.findByMatchIdAndUserId(100L, 10L)).thenReturn(Optional.of(viewer));
        when(turnActionRuleService.isMatchActive(match)).thenReturn(true);
        when(turnActionRuleService.isMatchStarted(match)).thenReturn(true);
        when(turnActionRuleService.isCurrentTurnPlayer(match, 10L)).thenReturn(true);
        when(turnActionRuleService.hasBlockingPendingDecision(100L, 10L)).thenReturn(false);
        when(turnActionRuleService.hasAnyPendingDecision(100L)).thenReturn(false);
    }
}
