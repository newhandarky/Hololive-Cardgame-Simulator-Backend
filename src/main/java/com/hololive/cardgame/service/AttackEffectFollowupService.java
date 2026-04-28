package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AttackEffectFollowupService {

    private final HoloxRevealResolver holoxRevealResolver;
    private final Hbp02039SupportRecoveryResolver hbp02039SupportRecoveryResolver;
    private final Hbp02040LifeLossResolver hbp02040LifeLossResolver;
    private final DamagePreventionResolver damagePreventionResolver;
    private final OfficialCardArtExtraResolver officialCardArtExtraResolver;
    private final OfficialOshiArtReactiveResolver officialOshiArtReactiveResolver;

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
        List<Map<String, Object>> officialCardArtExtraEffects = extractExecutedEffectSummaries(officialCardArtExtraSummary);
        Map<String, Object> officialOshiArtReactiveSummary = officialOshiArtReactiveResolver.resolve(
            context,
            officialCardArtExtraSummary
        );
        List<Map<String, Object>> officialOshiArtReactiveEffects = extractExecutedEffectSummaries(officialOshiArtReactiveSummary);

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

    private List<Map<String, Object>> extractExecutedEffectSummaries(Map<String, Object> effectSummary) {
        if (effectSummary == null || effectSummary.isEmpty()) {
            return List.of();
        }
        Object executed = effectSummary.get("executedEffects");
        if (!(executed instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Object effect : list) {
            if (effect instanceof Map<?, ?> effectMap) {
                Map<String, Object> casted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : effectMap.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    casted.put(entry.getKey().toString(), entry.getValue());
                }
                summaries.add(casted);
            }
        }
        return summaries;
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
