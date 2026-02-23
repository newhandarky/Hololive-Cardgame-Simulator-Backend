package com.hololive.cardgame.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeckSummaryResponse {

    private Long id;
    private String name;
    private String format;
    private boolean active;
    private Integer version;
    private Integer totalCards;
    private Integer distinctCards;
    private LocalDateTime updatedAt;
}
