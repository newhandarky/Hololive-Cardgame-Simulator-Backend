package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackDamageApplicationServiceTest {

    private final MatchEffectDamageService matchEffectDamageService = mock(MatchEffectDamageService.class);
    private final GameActionExecutor gameActionExecutor = mock(GameActionExecutor.class);
    private final AttackDamageApplicationService service =
        new AttackDamageApplicationService(matchEffectDamageService, gameActionExecutor);

    @Test
    void applyDamageShouldApplyArtDamageWhenOpponentHolomemExistsAndDamageIsPositive() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "ART_DAMAGE");
        summary.put("damageApplied", 120);
        summary.put("lostLifeCardInstanceId", 701L);
        when(matchEffectDamageService.applyArtDamage(100L, 10L, 120, 801L, true)).thenReturn(summary);

        AttackDamageApplicationResult result = service.applyDamage(AttackDamageApplicationContext.attackArt(
            100L,
            10L,
            20L,
            120,
            801L,
            true
        ));

        assertThat(result.artSummary()).containsEntry("damageApplied", 120);
        assertThat(result.lostLifeCardInstanceId()).isEqualTo(701L);
        assertThat(result.hasLifeLoss()).isTrue();
        verify(matchEffectDamageService).applyArtDamage(100L, 10L, 120, 801L, true);
        verifyNoInteractions(gameActionExecutor);
    }

    @Test
    void applyDamageShouldReturnPreventedSummaryWhenOpponentHolomemExistsAndDamageIsZero() {
        AttackDamageApplicationResult result = service.applyDamage(AttackDamageApplicationContext.attackArt(
            100L,
            10L,
            20L,
            0,
            801L,
            true
        ));

        assertThat(result.isPrevented()).isTrue();
        assertThat(result.hasLifeLoss()).isFalse();
        assertThat(result.artSummary())
            .containsEntry("effectType", "ART_DAMAGE_PREVENTED")
            .containsEntry("damageRequested", 0)
            .containsEntry("damageApplied", 0)
            .containsEntry("reason", "傷害已由受傷 Gift 抵銷")
            .containsEntry("lifeReduced", false);
        verifyNoInteractions(matchEffectDamageService, gameActionExecutor);
    }

    @Test
    void applyDamageShouldLoseLifeWhenOpponentHasNoHolomem() {
        when(gameActionExecutor.execute(any(EffectContext.class), anyList())).thenReturn(List.of(
            ActionResult.success("REDUCE_LIFE", Map.of("lifeCardInstanceIds", List.of(901L)))
        ));

        AttackDamageApplicationResult result = service.applyDamage(AttackDamageApplicationContext.attackArt(
            100L,
            10L,
            20L,
            120,
            null,
            false
        ));

        assertThat(result.isFallbackLifeLoss()).isTrue();
        assertThat(result.hasLifeLoss()).isTrue();
        assertThat(result.lostLifeCardInstanceId()).isEqualTo(901L);
        assertThat(result.artSummary())
            .containsEntry("effectType", "ART_DAMAGE_FALLBACK")
            .containsEntry("damageRequested", 120)
            .containsEntry("damageApplied", 0)
            .containsEntry("reason", "對手場上無 Holomen，改為扣除 1 點 LIFE")
            .containsEntry("lifeReduced", true)
            .containsEntry("lostLifeCardInstanceId", 901L);
        verifyNoInteractions(matchEffectDamageService);
    }

    @Test
    void applyDamageShouldAcceptStringLifeCardInstanceIdFromExecutor() {
        when(gameActionExecutor.execute(any(EffectContext.class), anyList())).thenReturn(List.of(
            ActionResult.success("REDUCE_LIFE", Map.of("lifeCardInstanceIds", List.of("901")))
        ));

        AttackDamageApplicationResult result = service.applyDamage(AttackDamageApplicationContext.attackArt(
            100L,
            10L,
            20L,
            120,
            null,
            false
        ));

        assertThat(result.lostLifeCardInstanceId()).isEqualTo(901L);
    }

    @Test
    void applyDamageShouldRejectWhenOpponentHasNoLifeToLose() {
        when(gameActionExecutor.execute(any(EffectContext.class), anyList())).thenReturn(List.of(
            ActionResult.failure("REDUCE_LIFE", "NO_LIFE_CARD")
        ));

        assertThatThrownBy(() -> service.applyDamage(AttackDamageApplicationContext.attackArt(
            100L,
            10L,
            20L,
            120,
            null,
            false
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("對手沒有可失去的 LIFE");
    }
}
