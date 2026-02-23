package com.hololive.cardgame.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeckValidationResponse {

    private boolean valid;
    private Integer totalCount;
    private Integer oshiCount;
    private Integer mainDeckCount;
    private Integer cheerDeckCount;
    private List<DeckValidationErrorResponse> errors;
}
