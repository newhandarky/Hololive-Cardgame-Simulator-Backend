package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record BloomValidationResult(
    boolean allowed,
    GameErrorCode errorCode,
    String message,
    Map<String, Object> details
) {

    public static BloomValidationResult permitted() {
        return new BloomValidationResult(true, null, null, Collections.emptyMap());
    }

    public static BloomValidationResult blocked(GameErrorCode errorCode, String message) {
        return blocked(errorCode, message, Collections.emptyMap());
    }

    public static BloomValidationResult blocked(
        GameErrorCode errorCode,
        String message,
        Map<String, Object> details
    ) {
        return new BloomValidationResult(
            false,
            errorCode,
            message,
            details == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(details))
        );
    }
}
