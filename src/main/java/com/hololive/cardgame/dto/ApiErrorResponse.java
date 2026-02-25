package com.hololive.cardgame.dto;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;

@Data
public class ApiErrorResponse {

    private String code;
    private String message;
    private Map<String, Object> details;
    private String path;
    private LocalDateTime timestamp;
}

