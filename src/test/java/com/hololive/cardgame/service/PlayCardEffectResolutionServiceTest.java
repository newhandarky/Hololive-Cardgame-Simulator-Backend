package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class PlayCardEffectResolutionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchGiftTriggerService giftTriggerService = mock(MatchGiftTriggerService.class);
    private final MatchEventHookService eventHookService = mock(MatchEventHookService.class);
    private final PlayCardEffectResolutionService service = new PlayCardEffectResolutionService(
        jdbcTemplate,
        new ObjectMapper(),
        giftTriggerService,
        eventHookService
    );

    @Test
    void resolveShouldDeferFollowupDuringOpeningReset() {
        PlayCardAction action = action(true);
        PlayCardResolutionResult resolutionResult = resolutionResult(true);

        PlayCardEffectResolution result = service.resolve(action, resolutionResult);

        assertThat(result.deferredUntilLiveStart()).isTrue();
        assertThat(result.triggerSummary()).containsEntry("deferredUntilLiveStart", true);
        assertThat(result.giftTriggeredEffects()).isEmpty();
        assertThat(result.giftEffectSummary()).isEmpty();
        assertThat(result.hasPendingInteraction()).isFalse();
        verify(eventHookService, never()).onHolomemEnter(any(), any(), any(), any(), any());
        verify(giftTriggerService, never()).previewGiftTriggeredEffectsOnStageEnter(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void resolveShouldRunEnterHookAndGiftPreviewDuringMainPhase() {
        PlayCardAction action = action(false);
        PlayCardResolutionResult resolutionResult = resolutionResult(false);
        when(eventHookService.onHolomemEnter(100L, 10L, "hBP01-001", 701L, "BACK"))
            .thenReturn(Map.of("triggered", true));
        when(giftTriggerService.previewGiftTriggeredEffectsOnStageEnter(100L, 10L, 701L, "BACK", 4))
            .thenReturn(List.of());

        PlayCardEffectResolution result = service.resolve(action, resolutionResult);

        assertThat(result.deferredUntilLiveStart()).isFalse();
        assertThat(result.triggerSummary()).containsEntry("triggered", true);
        assertThat(result.giftEffectSummary()).containsEntry("sourceActionType", "GIFT");
        assertThat(result.giftEffectSummary()).containsEntry("deferred", false);
        assertThat(result.hasPendingInteraction()).isFalse();
        assertThat(result.triggerResolutionOrder()).hasSize(2);
    }

    @Test
    void resolveShouldCreateGiftConfirmPendingDecisionWhenGiftTriggersExist() {
        PlayCardAction action = action(false);
        PlayCardResolutionResult resolutionResult = resolutionResult(false);
        Map<String, Object> giftTrigger = Map.of(
            "triggerType",
            "STAGE_ENTER",
            "sourceCardInstanceId",
            701L,
            "triggerTargetCardInstanceId",
            701L,
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK",
            "requestedEffects",
            List.of("DRAW"),
            "rawText",
            "raw gift text"
        );
        when(eventHookService.onHolomemEnter(100L, 10L, "hBP01-001", 701L, "BACK"))
            .thenReturn(Map.of());
        when(giftTriggerService.previewGiftTriggeredEffectsOnStageEnter(100L, 10L, 701L, "BACK", 4))
            .thenReturn(List.of(giftTrigger));
        when(jdbcTemplate.queryForObject(contains("FROM match_pending_decisions"), eq(Integer.class), eq(100L), eq(10L)))
            .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(100L), eq(10L), eq(701L)))
            .thenReturn(null);
        when(
            jdbcTemplate.query(
                contains("INSERT INTO match_pending_decisions"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(10L),
                eq("TRIGGER_EFFECT_CONFIRM"),
                eq("GIFT"),
                eq(701L),
                eq("hBP01-001"),
                eq("GIFT_TRIGGER"),
                eq(0),
                eq(0),
                eq("PENDING"),
                contains("\"cards\":[{\"cardInstanceId\":701,\"cardId\":\"hBP01-001\",\"zone\":\"BACK\"")
            )
        ).thenReturn(900L);

        PlayCardEffectResolution result = service.resolve(action, resolutionResult);

        assertThat(result.hasPendingInteraction()).isTrue();
        assertThat(result.pendingInteractionDecisionId()).isEqualTo(900L);
        assertThat(result.pendingInteractionDecisionType()).isEqualTo("TRIGGER_EFFECT_CONFIRM");
        assertThat(result.giftEffectSummary()).containsEntry("deferred", true);
        assertThat(result.giftEffectSummary()).containsEntry("requestedEffects", List.of("DRAW"));
        verify(jdbcTemplate).query(
            contains("INSERT INTO match_pending_decisions"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(10L),
            eq("TRIGGER_EFFECT_CONFIRM"),
            eq("GIFT"),
            eq(701L),
            eq("hBP01-001"),
            eq("GIFT_TRIGGER"),
            eq(0),
            eq(0),
            eq("PENDING"),
            contains("\"cards\":[{\"cardInstanceId\":701,\"cardId\":\"hBP01-001\",\"zone\":\"BACK\"")
        );
    }

    private PlayCardAction action(boolean openingReset) {
        return new PlayCardAction(
            "PLAY_CARD",
            100L,
            10L,
            701L,
            "BACK",
            4,
            openingReset,
            PlayCardAction.ActionSource.TEST,
            "trace-play-card",
            "idem-play-card",
            LocalDateTime.now()
        );
    }

    private PlayCardResolutionResult resolutionResult(boolean openingReset) {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        return new PlayCardResolutionResult(
            match,
            10L,
            4,
            701L,
            "hBP01-001",
            "HAND",
            "BACK",
            501L,
            4,
            openingReset,
            "DEBUT",
            openingReset
        );
    }
}
