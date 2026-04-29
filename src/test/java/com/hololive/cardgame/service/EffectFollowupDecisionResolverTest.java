package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EffectFollowupDecisionResolverTest {

    private final EffectPostTriggerPendingService effectPostTriggerPendingService = mock(EffectPostTriggerPendingService.class);
    private final FollowupInteractionContextResolver followupInteractionContextResolver = mock(FollowupInteractionContextResolver.class);
    private final FollowupInteractionPendingDecisionWriter followupInteractionPendingDecisionWriter =
        mock(FollowupInteractionPendingDecisionWriter.class);
    private final EffectFollowupDecisionResolver resolver = new EffectFollowupDecisionResolver(
        effectPostTriggerPendingService,
        followupInteractionContextResolver,
        followupInteractionPendingDecisionWriter
    );

    @Test
    void resolvePostTriggerOrInteractionShouldPreferPostTriggerConfirmDecision() {
        Map<String, Object> effectSummary = Map.of("executedEffects", List.of());
        FollowupInteractionDecision postTriggerDecision = new FollowupInteractionDecision(101L, "TRIGGER_EFFECT_CONFIRM");
        when(effectPostTriggerPendingService.createEffectPostTriggerConfirmPendingInteractionIfNeeded(
            1L,
            10L,
            "PLAY_SUPPORT",
            501L,
            "hBP01-001",
            effectSummary,
            3
        )).thenReturn(postTriggerDecision);

        FollowupInteractionDecision decision = resolver.resolvePostTriggerOrInteraction(
            1L,
            10L,
            "PLAY_SUPPORT",
            501L,
            "hBP01-001",
            "LOOK_TOP_DECK",
            effectSummary,
            3
        );

        assertThat(decision).isEqualTo(postTriggerDecision);
        verify(followupInteractionContextResolver, never()).resolve(1L, 10L, effectSummary);
    }

    @Test
    void resolvePostTriggerOrInteractionShouldFallbackToFollowupInteractionDecision() {
        Map<String, Object> effectSummary = Map.of("lookedCards", List.of());
        FollowupInteractionContext context = new FollowupInteractionContext(
            "LOOK_TOP_DECK",
            "查看牌庫頂",
            "選擇保留在牌庫頂的卡片；若不選擇則放到底部。",
            0,
            1,
            List.of(Map.of("cardInstanceId", 801L, "cardId", "hBP02-001")),
            List.of(801L),
            List.of("TOP", "BOTTOM"),
            801L,
            "hBP02-001"
        );
        FollowupInteractionDecision followupDecision = new FollowupInteractionDecision(202L, "LOOK_TOP_DECK");
        when(effectPostTriggerPendingService.createEffectPostTriggerConfirmPendingInteractionIfNeeded(
            1L,
            10L,
            "PLAY_SUPPORT",
            501L,
            "hBP01-001",
            effectSummary,
            3
        )).thenReturn(null);
        when(followupInteractionContextResolver.resolve(1L, 10L, effectSummary)).thenReturn(context);
        when(followupInteractionPendingDecisionWriter.create(
            1L,
            10L,
            "PLAY_SUPPORT",
            501L,
            "hBP01-001",
            "LOOK_TOP_DECK",
            context
        )).thenReturn(followupDecision);

        FollowupInteractionDecision decision = resolver.resolvePostTriggerOrInteraction(
            1L,
            10L,
            "PLAY_SUPPORT",
            501L,
            "hBP01-001",
            "LOOK_TOP_DECK",
            effectSummary,
            3
        );

        assertThat(decision).isEqualTo(followupDecision);
    }
}
