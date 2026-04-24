package com.hololive.cardgame.service;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MatchTriggeredCombatEffectService {

    private final MatchEffectService matchEffectService;

    public MatchTriggeredCombatEffectService(MatchEffectService matchEffectService) {
        this.matchEffectService = matchEffectService;
    }

    public Map<String, Object> resolveTriggeredGiftDamagePrevention(
        Long matchId,
        Long defendingUserId,
        Long attackingUserId,
        Long sourceCardInstanceId,
        Long targetCardInstanceId,
        int turnNumber,
        int incomingDamage
    ) {
        return matchEffectService.resolveTriggeredGiftDamagePrevention(
            matchId,
            defendingUserId,
            attackingUserId,
            sourceCardInstanceId,
            targetCardInstanceId,
            turnNumber,
            incomingDamage
        );
    }

    public Map<String, Object> applyArtDownTriggeredEffects(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        String artEffectJsonText
    ) {
        return matchEffectService.applyArtDownTriggeredEffects(
            matchId,
            userId,
            attackerCardInstanceId,
            artEffectJsonText
        );
    }
}
