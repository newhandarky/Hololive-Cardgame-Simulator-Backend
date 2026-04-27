package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttachCheerValidationResult(
    boolean allowed,
    GameErrorCode errorCode,
    String message,
    Map<String, Object> details
) {

    public static AttachCheerValidationResult permitted() {
        return new AttachCheerValidationResult(true, null, null, Collections.emptyMap());
    }

    public static AttachCheerValidationResult blocked(GameErrorCode errorCode, String message) {
        return blocked(errorCode, message, Collections.emptyMap());
    }

    public static AttachCheerValidationResult blocked(
        GameErrorCode errorCode,
        String message,
        Map<String, Object> details
    ) {
        return new AttachCheerValidationResult(
            false,
            errorCode,
            message,
            details == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(details))
        );
    }
}
