package com.hololive.cardgame.dto;

import java.util.List;
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

    public ZoneCardInstanceResponse(
        Long cardInstanceId,
        String cardId,
        String zone,
        Integer positionIndex,
        Long ownerUserId,
        Boolean faceDown
    ) {
        this(cardInstanceId, cardId, zone, positionIndex, ownerUserId, faceDown, 1, List.of(cardInstanceId));
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
        List<Long> defaultStackIds = cardInstanceId == null ? List.of() : List.of(cardInstanceId);
        this.cardInstanceId = cardInstanceId;
        this.cardId = cardId;
        this.zone = zone;
        this.positionIndex = positionIndex;
        this.ownerUserId = ownerUserId;
        this.faceDown = faceDown;
        this.stackDepth = stackDepth == null || stackDepth <= 0 ? 1 : stackDepth;
        this.stackCardInstanceIds = stackCardInstanceIds == null ? defaultStackIds : List.copyOf(stackCardInstanceIds);
    }
}
