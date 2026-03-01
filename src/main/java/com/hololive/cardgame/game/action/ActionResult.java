package com.hololive.cardgame.game.action;

import java.util.Map;

public record ActionResult(
    String actionType,
    boolean success,
    Map<String, Object> details
) {
    public static ActionResult success(String actionType, Map<String, Object> details) {
        return new ActionResult(actionType, true, details == null ? Map.of() : Map.copyOf(details));
    }

    public static ActionResult failure(String actionType, String reason) {
        return new ActionResult(actionType, false, Map.of("reason", reason));
    }
}
