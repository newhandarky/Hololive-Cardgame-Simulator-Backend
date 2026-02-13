package com.hololive.cardgame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LineLoginResponse {
    private String token;
    private Long userId;
    private String displayName;
}

