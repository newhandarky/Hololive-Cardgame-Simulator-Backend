package com.hololive.cardgame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeckValidationErrorResponse {

    private String code;
    private String message;
}
