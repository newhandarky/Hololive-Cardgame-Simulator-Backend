package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackDefenderGiftFollowupServiceTest {

    private final MatchGiftTriggerService matchGiftTriggerService = mock(MatchGiftTriggerService.class);
    private final AttackDefenderGiftFollowupService.OfficialOshiSelfDownedEffectResolver oshiResolver =
        mock(AttackDefenderGiftFollowupService.OfficialOshiSelfDownedEffectResolver.class);
    private final AttackDefenderGiftFollowupService service =
        new AttackDefenderGiftFollowupService(matchGiftTriggerService, oshiResolver);

    @Test
    void resolveFollowupShouldReturnEmptyResultWhenNoHolomemDowned() {
        AttackDefenderGiftFollowupContext context = context(false, Map.of(), List.of());

        AttackDefenderGiftFollowupResult result = service.resolveFollowup(context);

        assertThat(result.hasOfficialOshiSelfDownedSummary()).isFalse();
        assertThat(result.hasDefenderGiftTriggeredEffects()).isFalse();
        assertThat(result.downedTargetCardId()).isEqualTo("hBP01-001");
        assertThat(result.downedTargetZone()).isEqualTo("CENTER");
        verify(oshiResolver, never()).resolveOfficialOshiSelfDownedEffects(context);
        verify(matchGiftTriggerService, never())
            .previewGiftTriggeredEffectsOnSelfDowned(100L, 20L, 801L, "CENTER", 3, Map.of());
        verify(matchGiftTriggerService, never())
            .previewGiftTriggeredEffectsOnAllyDowned(100L, 20L, 801L, "CENTER", 3);
    }

    @Test
    void resolveFollowupShouldBuildSelfAndAllyDownedGiftPreviewsWhenDowned() {
        Map<String, Object> holderSnapshot = holderSnapshot();
        Map<String, Object> oshiSummary = summary("effectType", "OSHI_REACTIVE_SELF_DOWNED_EFFECTS", "applied", true);
        Map<String, Object> selfDownedPreview = summary("triggerType", "SELF_DOWNED", "giftHolderCardId", "hSD09-007");
        Map<String, Object> allyDownedPreview = summary("triggerType", "ALLY_DOWNED", "giftHolderCardId", "hSD08-005");
        AttackDefenderGiftFollowupContext context = context(true, holderSnapshot, List.of());
        when(oshiResolver.resolveOfficialOshiSelfDownedEffects(context)).thenReturn(oshiSummary);
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnSelfDowned(100L, 20L, 801L, "CENTER", 3, holderSnapshot))
            .thenReturn(List.of(selfDownedPreview));
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnAllyDowned(100L, 20L, 801L, "CENTER", 3))
            .thenReturn(List.of(allyDownedPreview));

        AttackDefenderGiftFollowupResult result = service.resolveFollowup(context);

        assertThat(result.officialOshiSelfDownedSummary()).containsEntry("applied", true);
        assertThat(result.defenderGiftTriggeredEffects()).containsExactly(selfDownedPreview, allyDownedPreview);
        assertThat(result.hasOfficialOshiSelfDownedSummary()).isTrue();
        assertThat(result.hasDefenderGiftTriggeredEffects()).isTrue();
    }

    @Test
    void resolveFollowupShouldBuildHbp01124FanPreviewFromSnapshots() {
        Map<String, Object> holderSnapshot = holderSnapshot();
        Map<String, Object> fanSnapshot = summary(
            "supportCardInstanceId", 910L,
            "supportCardId", "HBP01-124",
            "rawText", ""
        );
        AttackDefenderGiftFollowupContext context = context(true, holderSnapshot, List.of(fanSnapshot));
        when(oshiResolver.resolveOfficialOshiSelfDownedEffects(context)).thenReturn(Map.of());
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnSelfDowned(100L, 20L, 801L, "CENTER", 3, holderSnapshot))
            .thenReturn(List.of());
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnAllyDowned(100L, 20L, 801L, "CENTER", 3))
            .thenReturn(List.of());

        AttackDefenderGiftFollowupResult result = service.resolveFollowup(context);

        assertThat(result.defenderGiftTriggeredEffects()).hasSize(1);
        assertThat(result.defenderGiftTriggeredEffects().get(0))
            .containsEntry("triggerType", "SELF_DOWNED")
            .containsEntry("giftHolderHolomemId", 701L)
            .containsEntry("giftHolderCardInstanceId", 910L)
            .containsEntry("giftHolderCardId", "HBP01-124")
            .containsEntry("giftHolderZone", "CENTER")
            .containsEntry("sourceCardInstanceId", 801L)
            .containsEntry("triggerTargetCardInstanceId", 801L)
            .containsEntry("requestedEffects", List.of("REATTACH"))
            .containsEntry("giftHolderAttachedCheerCardInstanceIds", List.of(601L, 602L))
            .containsEntry("giftHolderAttachedCheerCardIds", List.of("hY01-001", "hY01-002"))
            .containsEntry("giftHolderStackCardInstanceIds", List.of(501L))
            .containsEntry("giftHolderStackCardIds", List.of("hBP01-001"));
    }

    @Test
    void resolveFollowupShouldKeepDownedTargetMetadata() {
        AttackDefenderGiftFollowupContext context = context(true, holderSnapshot(), List.of());
        when(oshiResolver.resolveOfficialOshiSelfDownedEffects(context)).thenReturn(Map.of());
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnSelfDowned(100L, 20L, 801L, "CENTER", 3, holderSnapshot()))
            .thenReturn(List.of());
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnAllyDowned(100L, 20L, 801L, "CENTER", 3))
            .thenReturn(List.of());

        AttackDefenderGiftFollowupResult result = service.resolveFollowup(context);

        assertThat(result.downedTargetCardId()).isEqualTo("hBP01-001");
        assertThat(result.downedTargetZone()).isEqualTo("CENTER");
    }

    @Test
    void resolveFollowupShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.resolveFollowup(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack defender gift followup");
    }

    private AttackDefenderGiftFollowupContext context(
        boolean hasDownedHolomem,
        Map<String, Object> holderSnapshot,
        List<Map<String, Object>> fanSupportSnapshots
    ) {
        return AttackDefenderGiftFollowupContext.attackArt(
            100L,
            20L,
            10L,
            3,
            hasDownedHolomem,
            801L,
            "hBP01-001",
            "CENTER",
            new AttackTargetHolomem(701L, 801L, "hBP01-001", "CENTER", "RED"),
            holderSnapshot,
            fanSupportSnapshots,
            summary("effectType", "ART_DAMAGE", "downed", true)
        );
    }

    private Map<String, Object> holderSnapshot() {
        return summary(
            "holomem_id", 701L,
            "attached_cheer_card_instance_ids", List.of(601L, "602"),
            "attached_cheer_card_ids", List.of("hY01-001", "hY01-002"),
            "stack_card_instance_ids", List.of(501L),
            "stack_card_ids", List.of("hBP01-001")
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
