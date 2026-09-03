package com.hololive.cardgame.dto;

/**
 * 指定 viewer 在目前對戰快照可否提交某個回合 action。
 */
public record ActionCapability(
    ActionCapabilityCode type,
    boolean enabled,
    ActionCapabilityReasonCode reasonCode
) {

    public static ActionCapability enabled(ActionCapabilityCode type) {
        return new ActionCapability(type, true, null);
    }

    public static ActionCapability disabled(ActionCapabilityCode type, ActionCapabilityReasonCode reasonCode) {
        return new ActionCapability(type, false, reasonCode);
    }
}
