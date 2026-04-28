package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

public record AttackDownContext(
    Long matchId,
    Long attackerUserId,
    Long opponentUserId,
    int turnNumber,
    Long attackerCardInstanceId,
    String attackerCardId,
    String artName,
    String artEffectJsonText,
    Long effectiveTargetCardInstanceId,
    boolean hasOpponentHolomem,
    Map<String, Object> artSummary,
    List<Map<String, Object>> officialCardArtExtraEffects,
    List<Map<String, Object>> officialOshiArtReactiveEffects
) {
    public static AttackDownContext attackArt(
        Long matchId,
        Long attackerUserId,
        Long opponentUserId,
        int turnNumber,
        Long attackerCardInstanceId,
        String attackerCardId,
        String artName,
        String artEffectJsonText,
        Long effectiveTargetCardInstanceId,
        boolean hasOpponentHolomem,
        Map<String, Object> artSummary,
        List<Map<String, Object>> officialCardArtExtraEffects,
        List<Map<String, Object>> officialOshiArtReactiveEffects
    ) {
        return new AttackDownContext(
            matchId,
            attackerUserId,
            opponentUserId,
            turnNumber,
            attackerCardInstanceId,
            attackerCardId,
            artName,
            artEffectJsonText,
            effectiveTargetCardInstanceId,
            hasOpponentHolomem,
            artSummary,
            officialCardArtExtraEffects,
            officialOshiArtReactiveEffects
        );
    }
}
