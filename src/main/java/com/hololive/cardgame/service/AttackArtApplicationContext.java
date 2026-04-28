package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import java.util.List;
import java.util.Map;

public record AttackArtApplicationContext(
    MatchEntity match,
    Long matchId,
    Long attackerUserId,
    Long defenderUserId,
    int turnNumber,
    Long attackerCardInstanceId,
    Long targetCardInstanceId,
    Long attackerHolomemId,
    String attackerZone,
    String attackerCardId,
    String attackerCurrentLevel,
    String attackerMainColor,
    String artName,
    Object artOrderIndex,
    String artCostCheerJsonText,
    String artEffectJsonText,
    AttackTargetHolomem targetHolomem,
    Map<String, Object> defenderSelfDownedHolderSnapshot,
    List<Map<String, Object>> defenderSelfDownedFanSupportSnapshots
) {
    public static AttackArtApplicationContext attackArt(
        MatchEntity match,
        Long matchId,
        Long attackerUserId,
        Long defenderUserId,
        int turnNumber,
        Long attackerCardInstanceId,
        Long targetCardInstanceId,
        Long attackerHolomemId,
        String attackerZone,
        String attackerCardId,
        String attackerCurrentLevel,
        String attackerMainColor,
        String artName,
        Object artOrderIndex,
        String artCostCheerJsonText,
        String artEffectJsonText,
        AttackTargetHolomem targetHolomem,
        Map<String, Object> defenderSelfDownedHolderSnapshot,
        List<Map<String, Object>> defenderSelfDownedFanSupportSnapshots
    ) {
        return new AttackArtApplicationContext(
            match,
            matchId,
            attackerUserId,
            defenderUserId,
            turnNumber,
            attackerCardInstanceId,
            targetCardInstanceId,
            attackerHolomemId,
            attackerZone,
            attackerCardId,
            attackerCurrentLevel,
            attackerMainColor,
            artName,
            artOrderIndex,
            artCostCheerJsonText,
            artEffectJsonText,
            targetHolomem,
            defenderSelfDownedHolderSnapshot,
            defenderSelfDownedFanSupportSnapshots
        );
    }
}
