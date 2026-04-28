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

class AttackDownServiceTest {

    private final MatchGiftTriggerService matchGiftTriggerService = mock(MatchGiftTriggerService.class);
    private final MatchTriggeredCombatEffectService matchTriggeredCombatEffectService =
        mock(MatchTriggeredCombatEffectService.class);
    private final AttackDownService service =
        new AttackDownService(matchGiftTriggerService, matchTriggeredCombatEffectService);

    @Test
    void resolveDownShouldPreviewArtTriggerAndReturnNoOpWhenNoHolomemDowned() {
        Map<String, Object> artSummary = summary("effectType", "ART_DAMAGE", "downed", false);
        Map<String, Object> artGiftPreview = summary("triggerType", "ART_USED", "cardId", "hBP01-001");
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnArt(100L, 10L, 501L, 801L, 3, "Bloom Shot"))
            .thenReturn(List.of(artGiftPreview));

        AttackDownResult result = service.resolveDown(context(artSummary, List.of(), List.of()));

        assertThat(result.hasDownedHolomem()).isFalse();
        assertThat(result.giftTriggeredEffects()).containsExactly(artGiftPreview);
        assertThat(result.artDownTriggeredEffectSummary())
            .containsEntry("triggerType", "ART_DOWNED_OPPONENT")
            .containsEntry("applied", false);
        assertThat(result.downEventPreview()).isNull();
        verify(matchGiftTriggerService).previewGiftTriggeredEffectsOnArt(100L, 10L, 501L, 801L, 3, "Bloom Shot");
        verify(matchGiftTriggerService, never())
            .previewGiftTriggeredEffectsOnDownedOpponent(100L, 10L, 501L, 801L, 3);
        verify(matchTriggeredCombatEffectService, never())
            .applyArtDownTriggeredEffects(100L, 10L, 501L, "[{\"damage\":50}]");
    }

    @Test
    void resolveDownShouldPreviewDownedOpponentAndApplyArtDownEffectsWhenDowned() {
        Map<String, Object> artSummary = summary("effectType", "ART_DAMAGE", "downed", true);
        Map<String, Object> artGiftPreview = summary("triggerType", "ART_USED");
        Map<String, Object> downedGiftPreview = summary("triggerType", "OPPONENT_DOWNED");
        Map<String, Object> downSummary = summary("triggerType", "ART_DOWNED_OPPONENT", "applied", true);
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnArt(100L, 10L, 501L, 801L, 3, "Bloom Shot"))
            .thenReturn(List.of(artGiftPreview));
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnDownedOpponent(100L, 10L, 501L, 801L, 3))
            .thenReturn(List.of(downedGiftPreview));
        when(matchTriggeredCombatEffectService.applyArtDownTriggeredEffects(100L, 10L, 501L, "[{\"damage\":50}]"))
            .thenReturn(downSummary);

        AttackDownResult result = service.resolveDown(context(artSummary, List.of(), List.of()));

        assertThat(result.hasDownedHolomem()).isTrue();
        assertThat(result.giftTriggeredEffects()).containsExactly(artGiftPreview, downedGiftPreview);
        assertThat(result.artDownTriggeredEffectSummary()).containsEntry("applied", true);
        verify(matchGiftTriggerService).previewGiftTriggeredEffectsOnDownedOpponent(100L, 10L, 501L, 801L, 3);
        verify(matchTriggeredCombatEffectService).applyArtDownTriggeredEffects(100L, 10L, 501L, "[{\"damage\":50}]");
    }

    @Test
    void resolveDownShouldDetectNestedDownedEffectAndMergeAdditionalSummariesForChecks() {
        Map<String, Object> artSummary = summary("effectType", "ART_DAMAGE", "downed", false);
        Map<String, Object> officialExtra = summary("effectType", "OFFICIAL_CARD_ART_EXTRA", "downed", false);
        Map<String, Object> oshiReactive = summary("effectType", "OFFICIAL_OSHI_ART_REACTIVE", "downed", true);
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnArt(100L, 10L, 501L, 801L, 3, "Bloom Shot"))
            .thenReturn(List.of());
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnDownedOpponent(100L, 10L, 501L, 801L, 3))
            .thenReturn(List.of());
        when(matchTriggeredCombatEffectService.applyArtDownTriggeredEffects(100L, 10L, 501L, "[{\"damage\":50}]"))
            .thenReturn(summary("applied", true));

        AttackDownResult result = service.resolveDown(context(
            artSummary,
            List.of(officialExtra),
            List.of(oshiReactive)
        ));

        assertThat(result.hasDownedHolomem()).isTrue();
        assertThat(result.attackSummaryForTriggeredChecks()).containsKey("executedEffects");
        assertThat(result.attackSummaryForTriggeredChecks().get("executedEffects"))
            .asList()
            .containsExactly(artSummary, officialExtra, oshiReactive);
    }

    @Test
    void resolveDownShouldExtractNestedDeferredDownEventPreview() {
        Map<String, Object> downEvent = summary(
            "triggered", true,
            "deferred", true,
            "downedCardInstanceId", 801L
        );
        Map<String, Object> nestedEffect = summary("effectType", "ART_DAMAGE", "downEvent", downEvent);
        Map<String, Object> artSummary = summary("executedEffects", List.of(nestedEffect));
        when(matchGiftTriggerService.previewGiftTriggeredEffectsOnArt(100L, 10L, 501L, 801L, 3, "Bloom Shot"))
            .thenReturn(List.of());

        AttackDownResult result = service.resolveDown(context(artSummary, List.of(), List.of()));

        assertThat(result.downEventPreview()).containsEntry("downedCardInstanceId", 801L);
        assertThat(result.hasDeferredDownEvent()).isTrue();
    }

    @Test
    void resolveDownShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.resolveDown(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack down");
    }

    private AttackDownContext context(
        Map<String, Object> artSummary,
        List<Map<String, Object>> officialCardArtExtraEffects,
        List<Map<String, Object>> officialOshiArtReactiveEffects
    ) {
        return AttackDownContext.attackArt(
            100L,
            10L,
            20L,
            3,
            501L,
            "hBP01-001",
            "Bloom Shot",
            "[{\"damage\":50}]",
            801L,
            true,
            artSummary,
            officialCardArtExtraEffects,
            officialOshiArtReactiveEffects
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
