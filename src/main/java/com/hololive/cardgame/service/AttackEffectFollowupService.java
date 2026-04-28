package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

public class AttackEffectFollowupService {

    private final HoloxRevealResolver holoxRevealResolver;
    private final Hbp02039SupportRecoveryResolver hbp02039SupportRecoveryResolver;
    private final Hbp02040LifeLossResolver hbp02040LifeLossResolver;
    private final DamagePreventionResolver damagePreventionResolver;
    private final OfficialCardArtExtraResolver officialCardArtExtraResolver;
    private final OfficialOshiArtReactiveResolver officialOshiArtReactiveResolver;
    private final AttackEffectSummaryExtractor effectSummaryExtractor;

    public AttackEffectFollowupService(
        HoloxRevealResolver holoxRevealResolver,
        Hbp02039SupportRecoveryResolver hbp02039SupportRecoveryResolver,
        Hbp02040LifeLossResolver hbp02040LifeLossResolver,
        DamagePreventionResolver damagePreventionResolver,
        OfficialCardArtExtraResolver officialCardArtExtraResolver,
        OfficialOshiArtReactiveResolver officialOshiArtReactiveResolver
    ) {
        this.holoxRevealResolver = holoxRevealResolver;
        this.hbp02039SupportRecoveryResolver = hbp02039SupportRecoveryResolver;
        this.hbp02040LifeLossResolver = hbp02040LifeLossResolver;
        this.damagePreventionResolver = damagePreventionResolver;
        this.officialCardArtExtraResolver = officialCardArtExtraResolver;
        this.officialOshiArtReactiveResolver = officialOshiArtReactiveResolver;
        this.effectSummaryExtractor = new AttackEffectSummaryExtractor();
    }

    public AttackEffectFollowupResult resolvePreDamage(AttackEffectFollowupContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack effect followup 缺少必要上下文");
        }

        HoloxRevealResult holoxRevealResult = holoxRevealResolver.resolve(context);
        HoloxSlotRevealSummary holoxSlotRevealSummary = holoxRevealResult == null ? null : holoxRevealResult.summary();
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

    public AttackEffectDamagePreventionResult resolveDamagePrevention(AttackEffectDamagePreventionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack effect damage prevention 缺少必要上下文");
        }
        if (!context.hasOpponentHolomem() || !context.hasTargetHolomem()) {
            return AttackEffectDamagePreventionResult.unchanged(context.totalDamage());
        }

        Map<String, Object> summary = damagePreventionResolver.resolve(context);
        if (summary == null || summary.isEmpty()) {
            return AttackEffectDamagePreventionResult.unchanged(context.totalDamage());
        }
        Integer damageAfter = asInt(summary.get("damageAfter"));
        return AttackEffectDamagePreventionResult.resolved(
            summary,
            damageAfter == null ? context.totalDamage() : Math.max(damageAfter, 0)
        );
    }

    public AttackEffectPostDamageResult resolvePostDamage(AttackEffectPostDamageContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack effect post damage 缺少必要上下文");
        }

        Map<String, Object> officialCardArtExtraSummary = officialCardArtExtraResolver.resolve(context);
        List<Map<String, Object>> officialCardArtExtraEffects =
            effectSummaryExtractor.extractExecutedEffectSummaries(officialCardArtExtraSummary);
        Map<String, Object> officialOshiArtReactiveSummary = officialOshiArtReactiveResolver.resolve(
            context,
            officialCardArtExtraSummary
        );
        List<Map<String, Object>> officialOshiArtReactiveEffects =
            effectSummaryExtractor.extractExecutedEffectSummaries(officialOshiArtReactiveSummary);

        return new AttackEffectPostDamageResult(
            officialCardArtExtraSummary,
            officialCardArtExtraEffects,
            officialOshiArtReactiveSummary,
            officialOshiArtReactiveEffects
        );
    }

    private Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public record HoloxRevealResult(
        HoloxSlotRevealSummary summary,
        int artBonus
    ) {
    }

    public interface HoloxRevealResolver {
        HoloxRevealResult resolve(AttackEffectFollowupContext context);
    }

    public interface Hbp02039SupportRecoveryResolver {
        Map<String, Object> resolve(AttackEffectFollowupContext context, HoloxSlotRevealSummary holoxSlotRevealSummary);
    }

    public interface Hbp02040LifeLossResolver {
        Map<String, Object> resolve(AttackEffectFollowupContext context, HoloxSlotRevealSummary holoxSlotRevealSummary);
    }

    public interface DamagePreventionResolver {
        Map<String, Object> resolve(AttackEffectDamagePreventionContext context);
    }

    public interface OfficialCardArtExtraResolver {
        Map<String, Object> resolve(AttackEffectPostDamageContext context);
    }

    public interface OfficialOshiArtReactiveResolver {
        Map<String, Object> resolve(AttackEffectPostDamageContext context, Map<String, Object> officialCardArtExtraSummary);
    }
}
