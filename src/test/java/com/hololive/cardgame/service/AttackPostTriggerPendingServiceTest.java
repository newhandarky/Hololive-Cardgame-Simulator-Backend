package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AttackPostTriggerPendingServiceTest {

    private final AttackPostTriggerPendingService.PendingDecisionCreator pendingDecisionCreator =
        mock(AttackPostTriggerPendingService.PendingDecisionCreator.class);
    private final AttackPostTriggerPendingService service =
        new AttackPostTriggerPendingService(pendingDecisionCreator);

    @Test
    void resolvePendingShouldBuildNonDeferredSummariesWhenNoTriggersExist() {
        AttackPostTriggerPendingContext context = context(List.of(), null, List.of());

        AttackPostTriggerPendingResult result = service.resolvePending(context);

        assertThat(result.postTriggerEffectSummary())
            .containsEntry("sourceActionType", "ATTACK_ART_POST_TRIGGER")
            .containsEntry("deferred", false)
            .containsEntry("requestedEffects", List.of());
        assertThat(result.defenderGiftEffectSummary())
            .containsEntry("sourceActionType", "GIFT")
            .containsEntry("deferred", false)
            .containsEntry("requestedEffects", List.of());
        assertThat(result.hasPostTriggerPendingInteraction()).isFalse();
        assertThat(result.hasDefenderGiftPendingInteraction()).isFalse();
        verify(pendingDecisionCreator, never()).createAttackPostTriggerPending(context, result.postTriggerEffectSummary());
        verify(pendingDecisionCreator, never()).createDefenderGiftPending(context, result.defenderGiftEffectSummary());
    }

    @Test
    void resolvePendingShouldCreateAttackPostTriggerPendingWhenGiftExists() {
        Map<String, Object> gift = summary("requestedEffects", List.of("DRAW"), "triggerType", "ART_USED");
        AttackPostTriggerPendingContext context = context(List.of(gift), null, List.of());
        AttackPendingDecision decision = new AttackPendingDecision(301L, "TRIGGER_EFFECT_CONFIRM");
        when(pendingDecisionCreator.createAttackPostTriggerPending(any(), any())).thenReturn(decision);

        AttackPostTriggerPendingResult result = service.resolvePending(context);

        assertThat(result.postTriggerEffectSummary())
            .containsEntry("deferred", true)
            .containsEntry("triggeredGifts", List.of(gift))
            .containsEntry("requestedEffects", List.of("DRAW"));
        assertThat(result.postTriggerConfirmDecision()).isEqualTo(decision);
        verify(pendingDecisionCreator).createAttackPostTriggerPending(context, result.postTriggerEffectSummary());
    }

    @Test
    void resolvePendingShouldCreateAttackPostTriggerPendingWhenDownEventExists() {
        Map<String, Object> downEvent = summary("triggered", true, "deferred", true, "requestedLifeLoss", 1);
        AttackPostTriggerPendingContext context = context(List.of(), downEvent, List.of());
        AttackPendingDecision decision = new AttackPendingDecision(302L, "TRIGGER_EFFECT_CONFIRM");
        when(pendingDecisionCreator.createAttackPostTriggerPending(any(), any())).thenReturn(decision);

        AttackPostTriggerPendingResult result = service.resolvePending(context);

        assertThat(result.postTriggerEffectSummary())
            .containsEntry("deferred", true)
            .containsEntry("downEvent", downEvent)
            .containsEntry("requestedEffects", List.of("DOWN_EVENT"));
        assertThat(result.postTriggerConfirmDecision()).isEqualTo(decision);
        verify(pendingDecisionCreator).createAttackPostTriggerPending(context, result.postTriggerEffectSummary());
    }

    @Test
    void resolvePendingShouldCreateDefenderGiftPendingWhenDefenderGiftExists() {
        Map<String, Object> defenderGift = summary("requestedEffects", List.of("REATTACH"), "triggerType", "SELF_DOWNED");
        AttackPostTriggerPendingContext context = context(List.of(), null, List.of(defenderGift));
        AttackPendingDecision decision = new AttackPendingDecision(401L, "TRIGGER_EFFECT_CONFIRM");
        when(pendingDecisionCreator.createDefenderGiftPending(any(), any())).thenReturn(decision);

        AttackPostTriggerPendingResult result = service.resolvePending(context);

        assertThat(result.defenderGiftEffectSummary())
            .containsEntry("deferred", true)
            .containsEntry("triggeredGifts", List.of(defenderGift))
            .containsEntry("requestedEffects", List.of("REATTACH"));
        assertThat(result.defenderGiftConfirmDecision()).isEqualTo(decision);
        verify(pendingDecisionCreator).createDefenderGiftPending(context, result.defenderGiftEffectSummary());
    }

    @Test
    void resolvePendingShouldCreateAttackPendingBeforeDefenderPending() {
        Map<String, Object> gift = summary("requestedEffects", List.of("DRAW"));
        Map<String, Object> defenderGift = summary("requestedEffects", List.of("REATTACH"));
        AttackPostTriggerPendingContext context = context(List.of(gift), null, List.of(defenderGift));
        when(pendingDecisionCreator.createAttackPostTriggerPending(any(), any()))
            .thenReturn(new AttackPendingDecision(301L, "TRIGGER_EFFECT_CONFIRM"));
        when(pendingDecisionCreator.createDefenderGiftPending(any(), any()))
            .thenReturn(new AttackPendingDecision(401L, "TRIGGER_EFFECT_CONFIRM"));

        AttackPostTriggerPendingResult result = service.resolvePending(context);

        InOrder inOrder = inOrder(pendingDecisionCreator);
        inOrder.verify(pendingDecisionCreator).createAttackPostTriggerPending(context, result.postTriggerEffectSummary());
        inOrder.verify(pendingDecisionCreator).createDefenderGiftPending(context, result.defenderGiftEffectSummary());
        assertThat(result.hasPostTriggerPendingInteraction()).isTrue();
        assertThat(result.hasDefenderGiftPendingInteraction()).isTrue();
    }

    @Test
    void resolvePendingShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.resolvePending(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack post trigger pending");
    }

    private AttackPostTriggerPendingContext context(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview,
        List<Map<String, Object>> defenderGiftTriggeredEffects
    ) {
        return AttackPostTriggerPendingContext.attackArt(
            100L,
            10L,
            20L,
            3,
            501L,
            "hBP01-001",
            801L,
            "hBP02-041",
            giftTriggeredEffects,
            downEventPreview,
            defenderGiftTriggeredEffects
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
