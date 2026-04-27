package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record EndTurnValidationResult(
    boolean allowed,
    GameErrorCode errorCode,
    String message,
    Map<String, Object> details
) {

    public static EndTurnValidationResult permitted() {
        return new EndTurnValidationResult(true, null, null, Collections.emptyMap());
    }

    public static EndTurnValidationResult blocked(GameErrorCode errorCode, String message) {
        return blocked(errorCode, message, Collections.emptyMap());
    }

    public static EndTurnValidationResult blocked(
        GameErrorCode errorCode,
        String message,
        Map<String, Object> details
    ) {
        return new EndTurnValidationResult(
            false,
            errorCode,
            message,
            details == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(details))
        );
    }
}
