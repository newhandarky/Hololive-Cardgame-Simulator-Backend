package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record HoloxSlotRevealSummary(
    boolean revealApplied,
    List<Long> revealedCardInstanceIds,
    List<String> revealedCardIds,
    int revealedHolomemCount,
    int artBonus,
    List<Long> archivedCardInstanceIds,
    List<String> archivedCardIds,
    List<Long> archivedSupportCardInstanceIds,
    List<String> archivedSupportCardIds,
    boolean revealedAllMembersSameBloomLevel,
    Integer sharedBloomLevel
) {
    static HoloxSlotRevealSummary empty() {
        return new HoloxSlotRevealSummary(
            false,
            List.of(),
            List.of(),
            0,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false,
            null
        );
    }

    Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revealApplied", revealApplied);
        payload.put("revealedCardInstanceIds", revealedCardInstanceIds);
        payload.put("revealedCardIds", revealedCardIds);
        payload.put("revealedHolomemCount", revealedHolomemCount);
        payload.put("artBonus", artBonus);
        payload.put("archivedCardInstanceIds", archivedCardInstanceIds);
        payload.put("archivedCardIds", archivedCardIds);
        payload.put("archivedSupportCardInstanceIds", archivedSupportCardInstanceIds);
        payload.put("archivedSupportCardIds", archivedSupportCardIds);
        payload.put("revealedAllMembersSameBloomLevel", revealedAllMembersSameBloomLevel);
        payload.put("sharedBloomLevel", sharedBloomLevel);
        return payload;
    }
}
