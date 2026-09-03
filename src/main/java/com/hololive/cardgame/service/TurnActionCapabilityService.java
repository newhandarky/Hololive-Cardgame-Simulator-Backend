package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.ActionCapability;
import com.hololive.cardgame.dto.ActionCapabilityCode;
import com.hololive.cardgame.dto.ActionCapabilityReasonCode;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.entity.MatchPlayerEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 將既有回合驗證共用規則投影為 viewer-specific action capabilities。
 */
@Service
public class TurnActionCapabilityService {

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final TurnActionRuleService turnActionRuleService;

    public TurnActionCapabilityService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        TurnActionRuleService turnActionRuleService
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.turnActionRuleService = turnActionRuleService;
    }

    @Transactional(readOnly = true)
    public List<ActionCapability> getCapabilities(Long matchId, Long viewerUserId) {
        MatchEntity match = matchRepository.findById(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
        MatchPlayerEntity viewer = matchPlayerRepository.findByMatchIdAndUserId(matchId, viewerUserId)
            .orElseThrow(() -> new IllegalStateException("你不在此房間中"));
        ActionCapabilityReasonCode sharedReason = sharedDisabledReason(match, matchId, viewerUserId);
        if (sharedReason != null) {
            return allDisabled(sharedReason);
        }

        MatchPhase phase = turnActionRuleService.parsePhase(match.getCurrentPhase());
        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        boolean drawCompleted = turnActionRuleService.hasDrawTurnAction(matchId, viewerUserId, turnNumber);
        boolean turnCheerCompleted = turnActionRuleService.hasTurnCheerAction(matchId, viewerUserId, turnNumber);
        boolean canSendTurnCheer = turnActionRuleService.canPerformTurnCheerAction(matchId, viewerUserId);
        List<ActionCapability> capabilities = new ArrayList<>();
        capabilities.add(drawCapability(phase, drawCompleted));
        capabilities.add(turnCheerCapability(phase, turnCheerCompleted, canSendTurnCheer));
        capabilities.add(advancePhaseCapability(match, viewer, phase, drawCompleted, turnCheerCompleted, canSendTurnCheer));
        capabilities.add(endTurnCapability(phase, drawCompleted, turnCheerCompleted, canSendTurnCheer));
        return List.copyOf(capabilities);
    }

    private ActionCapabilityReasonCode sharedDisabledReason(MatchEntity match, Long matchId, Long viewerUserId) {
        if (!turnActionRuleService.isMatchActive(match)) {
            return ActionCapabilityReasonCode.MATCH_NOT_ACTIVE;
        }
        if (!turnActionRuleService.isMatchStarted(match)) {
            return ActionCapabilityReasonCode.MATCH_NOT_STARTED;
        }
        if (!turnActionRuleService.isCurrentTurnPlayer(match, viewerUserId)) {
            return ActionCapabilityReasonCode.NOT_YOUR_TURN;
        }
        if (
            turnActionRuleService.hasBlockingPendingDecision(matchId, viewerUserId)
                || turnActionRuleService.hasAnyPendingDecision(matchId)
        ) {
            return ActionCapabilityReasonCode.PENDING_INTERACTION_BLOCKED;
        }
        return null;
    }

    private List<ActionCapability> allDisabled(ActionCapabilityReasonCode reasonCode) {
        return List.of(
            ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, reasonCode),
            ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, reasonCode),
            ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, reasonCode),
            ActionCapability.disabled(ActionCapabilityCode.END_TURN, reasonCode)
        );
    }

    private ActionCapability drawCapability(MatchPhase phase, boolean drawCompleted) {
        if (!isOneOf(phase, MatchPhase.MAIN, MatchPhase.DRAW)) {
            return ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED);
        }
        if (drawCompleted) {
            return ActionCapability.disabled(ActionCapabilityCode.DRAW_TURN, ActionCapabilityReasonCode.TURN_DRAW_ALREADY_USED);
        }
        return ActionCapability.enabled(ActionCapabilityCode.DRAW_TURN);
    }

    private ActionCapability turnCheerCapability(MatchPhase phase, boolean turnCheerCompleted, boolean canSendTurnCheer) {
        if (!isOneOf(phase, MatchPhase.MAIN, MatchPhase.CHEER)) {
            return ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED);
        }
        if (turnCheerCompleted) {
            return ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.TURN_CHEER_ALREADY_USED);
        }
        if (!canSendTurnCheer) {
            return ActionCapability.disabled(ActionCapabilityCode.SEND_TURN_CHEER, ActionCapabilityReasonCode.TURN_CHEER_UNAVAILABLE);
        }
        return ActionCapability.enabled(ActionCapabilityCode.SEND_TURN_CHEER);
    }

    private ActionCapability advancePhaseCapability(
        MatchEntity match,
        MatchPlayerEntity viewer,
        MatchPhase phase,
        boolean drawCompleted,
        boolean turnCheerCompleted,
        boolean canSendTurnCheer
    ) {
        if (phase == MatchPhase.RESET) {
            return viewer.isMulliganDone()
                && turnActionRuleService.hasOpeningCenterPlaced(match.getId(), viewer.getUserId())
                ? ActionCapability.enabled(ActionCapabilityCode.ADVANCE_PHASE)
                : ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.OPENING_SETUP_INCOMPLETE);
        }
        if (!isOneOf(phase, MatchPhase.MAIN, MatchPhase.PERFORMANCE)) {
            return ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED);
        }
        if (phase == MatchPhase.MAIN && (!drawCompleted || (canSendTurnCheer && !turnCheerCompleted))) {
            return ActionCapability.disabled(ActionCapabilityCode.ADVANCE_PHASE, ActionCapabilityReasonCode.TURN_ACTIONS_INCOMPLETE);
        }
        return ActionCapability.enabled(ActionCapabilityCode.ADVANCE_PHASE);
    }

    private ActionCapability endTurnCapability(
        MatchPhase phase,
        boolean drawCompleted,
        boolean turnCheerCompleted,
        boolean canSendTurnCheer
    ) {
        if (phase != MatchPhase.END) {
            return ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.PHASE_ACTION_NOT_ALLOWED);
        }
        if (!drawCompleted || (canSendTurnCheer && !turnCheerCompleted)) {
            return ActionCapability.disabled(ActionCapabilityCode.END_TURN, ActionCapabilityReasonCode.TURN_ACTIONS_INCOMPLETE);
        }
        return ActionCapability.enabled(ActionCapabilityCode.END_TURN);
    }

    private boolean isOneOf(MatchPhase phase, MatchPhase... allowedPhases) {
        for (MatchPhase allowedPhase : allowedPhases) {
            if (phase == allowedPhase) {
                return true;
            }
        }
        return false;
    }
}
