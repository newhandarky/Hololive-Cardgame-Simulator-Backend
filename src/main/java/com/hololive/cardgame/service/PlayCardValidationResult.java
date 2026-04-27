package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record PlayCardValidationResult(
    boolean allowed,
    GameErrorCode errorCode,
    String message,
    Map<String, Object> details
) {

    public static PlayCardValidationResult permitted() {
        return new PlayCardValidationResult(true, null, null, Collections.emptyMap());
    }

    public static PlayCardValidationResult blocked(GameErrorCode errorCode, String message) {
        return blocked(errorCode, message, Collections.emptyMap());
    }

    public static PlayCardValidationResult blocked(
        GameErrorCode errorCode,
        String message,
        Map<String, Object> details
    ) {
        return new PlayCardValidationResult(
            false,
            errorCode,
            message,
            details == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(details))
        );
    }
}
