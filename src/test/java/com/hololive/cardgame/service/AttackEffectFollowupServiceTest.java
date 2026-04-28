package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.service.AttackEffectFollowupService.HoloxRevealResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AttackEffectFollowupServiceTest {

    private final AttackEffectFollowupService.HoloxRevealResolver holoxRevealResolver =
        mock(AttackEffectFollowupService.HoloxRevealResolver.class);
    private final AttackEffectFollowupService.Hbp02039SupportRecoveryResolver hbp02039SupportRecoveryResolver =
        mock(AttackEffectFollowupService.Hbp02039SupportRecoveryResolver.class);
    private final AttackEffectFollowupService.Hbp02040LifeLossResolver hbp02040LifeLossResolver =
        mock(AttackEffectFollowupService.Hbp02040LifeLossResolver.class);
    private final AttackEffectFollowupService.DamagePreventionResolver damagePreventionResolver =
        mock(AttackEffectFollowupService.DamagePreventionResolver.class);
    private final AttackEffectFollowupService.OfficialCardArtExtraResolver officialCardArtExtraResolver =
        mock(AttackEffectFollowupService.OfficialCardArtExtraResolver.class);
    private final AttackEffectFollowupService.OfficialOshiArtReactiveResolver officialOshiArtReactiveResolver =
        mock(AttackEffectFollowupService.OfficialOshiArtReactiveResolver.class);
    private final AttackEffectFollowupService service = new AttackEffectFollowupService(
        holoxRevealResolver,
        hbp02039SupportRecoveryResolver,
        hbp02040LifeLossResolver,
        damagePreventionResolver,
        officialCardArtExtraResolver,
        officialOshiArtReactiveResolver
    );

    @Test
    void resolvePreDamageShouldResolveHoloxBeforeHbpFollowups() {
        AttackEffectFollowupContext context = context();
        HoloxSlotRevealSummary holoxSummary = HoloxSlotRevealSummary.empty();
        when(holoxRevealResolver.resolve(context)).thenReturn(new HoloxRevealResult(holoxSummary, 40));

        service.resolvePreDamage(context);

        InOrder inOrder = inOrder(
            holoxRevealResolver,
            hbp02039SupportRecoveryResolver,
            hbp02040LifeLossResolver
        );
        inOrder.verify(holoxRevealResolver).resolve(context);
        inOrder.verify(hbp02039SupportRecoveryResolver).resolve(context, holoxSummary);
        inOrder.verify(hbp02040LifeLossResolver).resolve(context, holoxSummary);
    }

    @Test
    void resolvePreDamageShouldUseHoloxArtBonus() {
        AttackEffectFollowupContext context = context();
        HoloxSlotRevealSummary holoxSummary = HoloxSlotRevealSummary.empty();
        when(holoxRevealResolver.resolve(context)).thenReturn(new HoloxRevealResult(holoxSummary, 60));

        AttackEffectFollowupResult result = service.resolvePreDamage(context);

        assertThat(result.holoxSlotRevealSummary()).isEqualTo(holoxSummary);
        assertThat(result.artBonus()).isEqualTo(60);
    }

    @Test
    void resolvePreDamageShouldReturnHbpSummaries() {
        AttackEffectFollowupContext context = context();
        HoloxSlotRevealSummary holoxSummary = HoloxSlotRevealSummary.empty();
        Map<String, Object> recovery = summary("effectType", "HBP02039_SUPPORT_RECOVERY");
        Map<String, Object> lifeLoss = summary("effectType", "HBP02040_LIFE_LOSS");
        when(holoxRevealResolver.resolve(context)).thenReturn(new HoloxRevealResult(holoxSummary, 20));
        when(hbp02039SupportRecoveryResolver.resolve(context, holoxSummary)).thenReturn(recovery);
        when(hbp02040LifeLossResolver.resolve(context, holoxSummary)).thenReturn(lifeLoss);

        AttackEffectFollowupResult result = service.resolvePreDamage(context);

        assertThat(result.hbp02039SupportRecovery()).isEqualTo(recovery);
        assertThat(result.hbp02040LifeLoss()).isEqualTo(lifeLoss);
    }

    @Test
    void resolvePreDamageShouldHandleMissingHoloxResult() {
        AttackEffectFollowupContext context = context();
        when(holoxRevealResolver.resolve(context)).thenReturn(null);

        AttackEffectFollowupResult result = service.resolvePreDamage(context);

        assertThat(result.holoxSlotRevealSummary()).isNull();
        assertThat(result.artBonus()).isZero();
    }

    @Test
    void resolvePreDamageShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.resolvePreDamage(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack effect followup");
    }

    @Test
    void resolveDamagePreventionShouldSkipWhenThereIsNoTargetHolomem() {
        AttackEffectDamagePreventionResult result = service.resolveDamagePrevention(
            damagePreventionContext(false, false, 80)
        );

        assertThat(result.defenderDamageReceivedGiftSummary()).isEmpty();
        assertThat(result.adjustedDamage()).isEqualTo(80);
        assertThat(result.actionLogRequired()).isFalse();
    }

    @Test
    void resolveDamagePreventionShouldReturnAdjustedDamageAndSummary() {
        AttackEffectDamagePreventionContext context = damagePreventionContext(true, true, 90);
        Map<String, Object> summary = summary("effectType", "DAMAGE_PREVENTION", "damageAfter", 50);
        when(damagePreventionResolver.resolve(context)).thenReturn(summary);

        AttackEffectDamagePreventionResult result = service.resolveDamagePrevention(context);

        assertThat(result.defenderDamageReceivedGiftSummary()).isEqualTo(summary);
        assertThat(result.adjustedDamage()).isEqualTo(50);
        assertThat(result.actionLogRequired()).isTrue();
    }

    @Test
    void resolveDamagePreventionShouldClampNegativeAdjustedDamage() {
        AttackEffectDamagePreventionContext context = damagePreventionContext(true, true, 30);
        when(damagePreventionResolver.resolve(context)).thenReturn(summary("damageAfter", -20));

        AttackEffectDamagePreventionResult result = service.resolveDamagePrevention(context);

        assertThat(result.adjustedDamage()).isZero();
        assertThat(result.actionLogRequired()).isTrue();
    }

    @Test
    void resolveDamagePreventionShouldKeepOriginalDamageWhenSummaryHasNoDamageAfter() {
        AttackEffectDamagePreventionContext context = damagePreventionContext(true, true, 70);
        when(damagePreventionResolver.resolve(context)).thenReturn(summary("effectType", "DAMAGE_PREVENTION"));

        AttackEffectDamagePreventionResult result = service.resolveDamagePrevention(context);

        assertThat(result.adjustedDamage()).isEqualTo(70);
        assertThat(result.actionLogRequired()).isTrue();
    }

    @Test
    void resolveDamagePreventionShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.resolveDamagePrevention(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack effect damage prevention");
    }

    @Test
    void resolvePostDamageShouldResolveOfficialCardBeforeOshiReactive() {
        AttackEffectPostDamageContext context = postDamageContext();
        Map<String, Object> officialCardSummary = summary("executedEffects", List.of(summary("effectType", "ART_EXTRA")));
        when(officialCardArtExtraResolver.resolve(context)).thenReturn(officialCardSummary);

        service.resolvePostDamage(context);

        InOrder inOrder = inOrder(officialCardArtExtraResolver, officialOshiArtReactiveResolver);
        inOrder.verify(officialCardArtExtraResolver).resolve(context);
        inOrder.verify(officialOshiArtReactiveResolver).resolve(context, officialCardSummary);
    }

    @Test
    void resolvePostDamageShouldReturnSummariesAndExecutedEffects() {
        AttackEffectPostDamageContext context = postDamageContext();
        Map<String, Object> cardEffect = summary("effectType", "ART_EXTRA");
        Map<String, Object> oshiEffect = summary("effectType", "OSHI_REACTIVE");
        Map<String, Object> officialCardSummary = summary("executedEffects", List.of(cardEffect));
        Map<String, Object> officialOshiSummary = summary("executedEffects", List.of(oshiEffect));
        when(officialCardArtExtraResolver.resolve(context)).thenReturn(officialCardSummary);
        when(officialOshiArtReactiveResolver.resolve(context, officialCardSummary)).thenReturn(officialOshiSummary);

        AttackEffectPostDamageResult result = service.resolvePostDamage(context);

        assertThat(result.officialCardArtExtraSummary()).isEqualTo(officialCardSummary);
        assertThat(result.officialCardArtExtraEffects()).containsExactly(cardEffect);
        assertThat(result.officialOshiArtReactiveSummary()).isEqualTo(officialOshiSummary);
        assertThat(result.officialOshiArtReactiveEffects()).containsExactly(oshiEffect);
    }

    @Test
    void resolvePostDamageShouldIgnoreNonMapExecutedEffects() {
        AttackEffectPostDamageContext context = postDamageContext();
        when(officialCardArtExtraResolver.resolve(context)).thenReturn(summary("executedEffects", List.of("ignored")));

        AttackEffectPostDamageResult result = service.resolvePostDamage(context);

        assertThat(result.officialCardArtExtraEffects()).isEmpty();
    }

    @Test
    void resolvePostDamageShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.resolvePostDamage(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack effect post damage");
    }

    private AttackEffectFollowupContext context() {
        return AttackEffectFollowupContext.preDamage(
            100L,
            10L,
            20L,
            3,
            501L,
            "HBP02-039",
            "ホロックスロット",
            "デッキの上から3枚を公開"
        );
    }

    private AttackEffectDamagePreventionContext damagePreventionContext(
        boolean hasOpponentHolomem,
        boolean hasTargetHolomem,
        int totalDamage
    ) {
        return AttackEffectDamagePreventionContext.attackArt(
            100L,
            10L,
            20L,
            3001L,
            4001L,
            3,
            totalDamage,
            hasOpponentHolomem,
            hasTargetHolomem
        );
    }

    private AttackEffectPostDamageContext postDamageContext() {
        return AttackEffectPostDamageContext.attackArt(
            100L,
            10L,
            20L,
            3,
            501L,
            4001L,
            "HBP01-087",
            "雨のマントラ",
            "BLUE",
            null,
            summary("damageApplied", 20)
        );
    }

    private Map<String, Object> summary(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
