package com.hololive.cardgame.service;

public class AttackActionLogService {

    public static final String ACTION_TYPE_ATTACK_ART = "ATTACK_ART";
    public static final String ACTION_TYPE_GIFT_TRIGGER = "GIFT_TRIGGER";

    private final AttackActionWriter actionWriter;

    public AttackActionLogService(AttackActionWriter actionWriter) {
        this.actionWriter = actionWriter;
    }

    public AttackActionLogResult appendAttackArt(AttackActionLogContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack action log 缺少必要上下文");
        }
        return append(context, ACTION_TYPE_ATTACK_ART);
    }

    public AttackActionLogResult appendGiftTrigger(AttackActionLogContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack gift trigger action log 缺少必要上下文");
        }
        return append(context, ACTION_TYPE_GIFT_TRIGGER);
    }

    private AttackActionLogResult append(AttackActionLogContext context, String actionType) {
        return actionWriter.appendAction(
            context.matchId(),
            context.userId(),
            actionType,
            context.payloadJson(),
            context.turnNumber()
        );
    }

    public interface AttackActionWriter {
        AttackActionLogResult appendAction(
            Long matchId,
            Long userId,
            String actionType,
            String payloadJson,
            int turnNumber
        );
    }
}
