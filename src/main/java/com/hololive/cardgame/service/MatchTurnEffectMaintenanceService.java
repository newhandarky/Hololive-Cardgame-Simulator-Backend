package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class MatchTurnEffectMaintenanceService {

    private final MatchEffectService matchEffectService;

    public MatchTurnEffectMaintenanceService(MatchEffectService matchEffectService) {
        this.matchEffectService = matchEffectService;
    }

    public int clearExpiredTurnEffects(Long matchId, int currentTurn) {
        return matchEffectService.clearExpiredTurnEffects(matchId, currentTurn);
    }
}
