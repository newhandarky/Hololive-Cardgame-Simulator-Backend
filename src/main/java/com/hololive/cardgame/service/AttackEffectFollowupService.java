package com.hololive.cardgame.service;

import java.util.Map;

public class AttackEffectFollowupService {

    private final HoloxRevealResolver holoxRevealResolver;
    private final Hbp02039SupportRecoveryResolver hbp02039SupportRecoveryResolver;
    private final Hbp02040LifeLossResolver hbp02040LifeLossResolver;

    public AttackEffectFollowupService(
        HoloxRevealResolver holoxRevealResolver,
        Hbp02039SupportRecoveryResolver hbp02039SupportRecoveryResolver,
        Hbp02040LifeLossResolver hbp02040LifeLossResolver
    ) {
        this.holoxRevealResolver = holoxRevealResolver;
        this.hbp02039SupportRecoveryResolver = hbp02039SupportRecoveryResolver;
        this.hbp02040LifeLossResolver = hbp02040LifeLossResolver;
    }

    public AttackEffectFollowupResult resolvePreDamage(AttackEffectFollowupContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack effect followup 缺少必要上下文");
        }

        HoloxRevealResult holoxRevealResult = holoxRevealResolver.resolve(context);
        Object holoxSlotRevealSummary = holoxRevealResult == null ? null : holoxRevealResult.summary();
        Map<String, Object> hbp02039SupportRecovery = hbp02039SupportRecoveryResolver.resolve(
            context,
            holoxSlotRevealSummary
        );
        Map<String, Object> hbp02040LifeLoss = hbp02040LifeLossResolver.resolve(
            context,
            holoxSlotRevealSummary
        );
        return new AttackEffectFollowupResult(
            holoxSlotRevealSummary,
            hbp02039SupportRecovery,
            hbp02040LifeLoss,
            holoxRevealResult == null ? 0 : holoxRevealResult.artBonus()
        );
    }

    public record HoloxRevealResult(
        Object summary,
        int artBonus
    ) {
    }

    public interface HoloxRevealResolver {
        HoloxRevealResult resolve(AttackEffectFollowupContext context);
    }

    public interface Hbp02039SupportRecoveryResolver {
        Map<String, Object> resolve(AttackEffectFollowupContext context, Object holoxSlotRevealSummary);
    }

    public interface Hbp02040LifeLossResolver {
        Map<String, Object> resolve(AttackEffectFollowupContext context, Object holoxSlotRevealSummary);
    }
}
