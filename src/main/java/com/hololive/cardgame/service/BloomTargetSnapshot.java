package com.hololive.cardgame.service;

public record BloomTargetSnapshot(
    Long holomemId,
    Long topCardInstanceId,
    String topCardId,
    String topCardName,
    String topLevelType,
    String zone,
    int damageTaken,
    Integer enteredTurnNumber,
    Integer lastBloomTurn,
    boolean stageActionLocked,
    Long extraBloomAllowanceId,
    boolean levelOverrideAllowed
) {
}
