package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class LineLoginRequest {
    private String idToken;
    private String displayName;
    private String avatarUrl;
}

