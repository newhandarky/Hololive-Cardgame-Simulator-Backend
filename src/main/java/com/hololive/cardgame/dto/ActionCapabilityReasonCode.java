package com.hololive.cardgame.dto;

/**
 * capability disabled 的機器可讀原因；前端自行決定顯示文案。
 */
public enum ActionCapabilityReasonCode {
    MATCH_NOT_ACTIVE,
    MATCH_NOT_STARTED,
    NOT_YOUR_TURN,
    PENDING_INTERACTION_BLOCKED,
    PHASE_ACTION_NOT_ALLOWED,
    TURN_DRAW_ALREADY_USED,
    TURN_CHEER_ALREADY_USED,
    TURN_CHEER_UNAVAILABLE,
    TURN_ACTIONS_INCOMPLETE,
    OPENING_SETUP_INCOMPLETE
}
