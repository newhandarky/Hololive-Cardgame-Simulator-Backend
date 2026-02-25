package com.hololive.cardgame.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class ZoneCardInstanceResponse {
    private Long cardInstanceId;
    private String cardId;
    private String zone;
    private Integer positionIndex;
    private Long ownerUserId;
    private Boolean faceDown;
    private Integer stackDepth;
    private List<Long> stackCardInstanceIds;
    private Integer currentHp;
    private Integer maxHp;
    private Integer damageTaken;
    private Integer cheerCount;
    private Map<String, Integer> cheerColorCounts;
    private Integer attachedSupportCount;

    public ZoneCardInstanceResponse(
        Long cardInstanceId,
        String cardId,
        String zone,
        Integer positionIndex,
        Long ownerUserId,
        Boolean faceDown
    ) {
        this(
            cardInstanceId,
            cardId,
            zone,
            positionIndex,
            ownerUserId,
            faceDown,
            1,
            List.of(cardInstanceId),
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public ZoneCardInstanceResponse(
        Long cardInstanceId,
        String cardId,
        String zone,
        Integer positionIndex,
        Long ownerUserId,
        Boolean faceDown,
        Integer stackDepth,
        List<Long> stackCardInstanceIds
    ) {
        this(
            cardInstanceId,
            cardId,
            zone,
            positionIndex,
            ownerUserId,
            faceDown,
            stackDepth,
            stackCardInstanceIds,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public ZoneCardInstanceResponse(
        Long cardInstanceId,
        String cardId,
        String zone,
        Integer positionIndex,
        Long ownerUserId,
        Boolean faceDown,
        Integer stackDepth,
        List<Long> stackCardInstanceIds,
        Integer currentHp,
        Integer maxHp,
        Integer damageTaken,
        Integer cheerCount,
        Map<String, Integer> cheerColorCounts,
        Integer attachedSupportCount
    ) {
        List<Long> defaultStackIds = cardInstanceId == null ? List.of() : List.of(cardInstanceId);
        this.cardInstanceId = cardInstanceId;
        this.cardId = cardId;
        this.zone = zone;
        this.positionIndex = positionIndex;
        this.ownerUserId = ownerUserId;
        this.faceDown = faceDown;
        this.stackDepth = stackDepth == null || stackDepth <= 0 ? 1 : stackDepth;
        this.stackCardInstanceIds = stackCardInstanceIds == null ? defaultStackIds : List.copyOf(stackCardInstanceIds);
        this.currentHp = currentHp;
        this.maxHp = maxHp;
        this.damageTaken = damageTaken;
        this.cheerCount = cheerCount;
        this.cheerColorCounts = cheerColorCounts == null ? Map.of() : Map.copyOf(cheerColorCounts);
        this.attachedSupportCount = attachedSupportCount == null ? 0 : attachedSupportCount;
    }
}
