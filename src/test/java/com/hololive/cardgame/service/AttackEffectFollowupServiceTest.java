package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.service.AttackEffectFollowupService.HoloxRevealResult;
import java.util.LinkedHashMap;
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
    private final AttackEffectFollowupService service = new AttackEffectFollowupService(
        holoxRevealResolver,
        hbp02039SupportRecoveryResolver,
        hbp02040LifeLossResolver
    );

    @Test
    void resolvePreDamageShouldResolveHoloxBeforeHbpFollowups() {
        AttackEffectFollowupContext context = context();
        Object holoxSummary = summary("revealApplied", true);
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
        Object holoxSummary = summary("revealApplied", true);
        when(holoxRevealResolver.resolve(context)).thenReturn(new HoloxRevealResult(holoxSummary, 60));

        AttackEffectFollowupResult result = service.resolvePreDamage(context);

        assertThat(result.holoxSlotRevealSummary()).isEqualTo(holoxSummary);
        assertThat(result.artBonus()).isEqualTo(60);
    }

    @Test
    void resolvePreDamageShouldReturnHbpSummaries() {
        AttackEffectFollowupContext context = context();
        Object holoxSummary = summary("revealApplied", true);
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

    private Map<String, Object> summary(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
