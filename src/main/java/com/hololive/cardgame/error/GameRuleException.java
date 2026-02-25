package com.hololive.cardgame.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class GameRuleException extends RuntimeException {

    private final GameErrorCode code;
    private final Map<String, Object> details;

    public GameRuleException(GameErrorCode code, String message) {
        this(code, message, Collections.emptyMap());
    }

    public GameRuleException(GameErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code == null ? GameErrorCode.CONFLICT : code;
        this.details = details == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public GameErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}

