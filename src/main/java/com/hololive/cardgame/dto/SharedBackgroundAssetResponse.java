package com.hololive.cardgame.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class SharedBackgroundAssetResponse {
    private Long id;
    private String category;
    private String imageUrl;
    private Long createdByUserId;
    private LocalDateTime createdAt;
}
