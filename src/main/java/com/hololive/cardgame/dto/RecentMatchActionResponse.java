package com.hololive.cardgame.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RecentMatchActionResponse {
    private Long actionId;
    private Long userId;
    private String actionType;
    private Integer turnNumber;
    private Integer actionOrder;
    private JsonNode payload;
    private LocalDateTime createdAt;
}

