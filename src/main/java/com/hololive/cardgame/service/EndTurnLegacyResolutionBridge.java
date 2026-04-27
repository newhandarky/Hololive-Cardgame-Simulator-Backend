package com.hololive.cardgame.service;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EndTurnLegacyResolutionBridge {

    private final MatchTurnEffectMaintenanceService matchTurnEffectMaintenanceService;
    private final MatchTurnLifecycleService matchTurnLifecycleService;

    public EndTurnLegacyResolutionBridge(
        MatchTurnEffectMaintenanceService matchTurnEffectMaintenanceService,
        MatchTurnLifecycleService matchTurnLifecycleService
    ) {
        this.matchTurnEffectMaintenanceService = matchTurnEffectMaintenanceService;
        this.matchTurnLifecycleService = matchTurnLifecycleService;
    }

    public EndTurnContext prepareContext(EndTurnAction action, EndTurnValidationContext validationContext) {
        int turnNumber = validationContext.currentTurnNumber();
        Long actorUserId = action.actorUserId();
        Long opponentUserId = validationContext.opponentUserId();

        int clearedEffectCount = matchTurnEffectMaintenanceService.clearExpiredTurnEffects(action.matchId(), turnNumber);
        int resetRestedCount = matchTurnLifecycleService.resetRestedHolomemsForTurnStart(
            action.matchId(),
            opponentUserId,
            turnNumber
        );
        Map<String, Object> centerReplenishSummary = matchTurnLifecycleService.resolveEndTurnCenterReplenishCycle(
            action.matchId(),
            actorUserId
        );

        return new EndTurnContext(
            validationContext.match(),
            actorUserId,
            opponentUserId,
            validationContext.currentPhase(),
            turnNumber,
            validationContext.requiredTurnActionSummary(),
            clearedEffectCount,
            resetRestedCount,
            centerReplenishSummary
        );
    }
}
