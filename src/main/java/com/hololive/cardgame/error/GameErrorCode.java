package com.hololive.cardgame.error;

import org.springframework.http.HttpStatus;

public enum GameErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    PENDING_INTERACTION_BLOCKED(HttpStatus.CONFLICT),
    TURN_DRAW_ALREADY_USED(HttpStatus.CONFLICT),
    TURN_CHEER_ALREADY_USED(HttpStatus.CONFLICT),
    TURN_ACTIONS_INCOMPLETE(HttpStatus.CONFLICT),
    LIMITED_FIRST_TURN(HttpStatus.CONFLICT),
    LIMITED_ALREADY_USED_THIS_TURN(HttpStatus.CONFLICT),
    BLOOM_NO_TARGET(HttpStatus.CONFLICT),
    BLOOM_INVALID_TARGET(HttpStatus.CONFLICT),
    PLAY_TO_STAGE_LEVEL_NOT_ALLOWED(HttpStatus.CONFLICT),
    OSHI_SKILL_INVALID_TYPE(HttpStatus.CONFLICT),
    OSHI_SKILL_NOT_FOUND(HttpStatus.CONFLICT),
    OSHI_SKILL_ALREADY_USED_THIS_TURN(HttpStatus.CONFLICT),
    OSHI_SKILL_SP_ALREADY_USED(HttpStatus.CONFLICT),
    OSHI_SKILL_HOLOPOWER_INSUFFICIENT(HttpStatus.CONFLICT),
    BATON_TOUCH_ALREADY_USED_THIS_TURN(HttpStatus.CONFLICT),
    STAGE_ACTION_LOCKED(HttpStatus.CONFLICT),
    NOT_YOUR_TURN(HttpStatus.CONFLICT),
    PHASE_ACTION_NOT_ALLOWED(HttpStatus.CONFLICT);

    private final HttpStatus httpStatus;

    GameErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
