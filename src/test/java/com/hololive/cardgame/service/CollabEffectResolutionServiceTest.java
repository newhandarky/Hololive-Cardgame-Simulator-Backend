package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

class CollabEffectResolutionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchTriggeredCardEffectService triggeredCardEffectService = mock(MatchTriggeredCardEffectService.class);
    private final MatchGiftTriggerService giftTriggerService = mock(MatchGiftTriggerService.class);
    private final MatchEventHookService eventHookService = mock(MatchEventHookService.class);
    private final CollabEffectResolutionService service = new CollabEffectResolutionService(
        jdbcTemplate,
        new ObjectMapper(),
        triggeredCardEffectService,
        giftTriggerService,
        eventHookService
    );

    @Test
    void resolveShouldReturnNonDeferredSummariesWhenNoCollabOrGiftEffect() {
        CollabAction action = action();
        CollabResolutionResult resolutionResult = resolutionResult();
        when(triggeredCardEffectService.previewCollabTriggeredEffect(100L, 10L, "hBP01-001", 701L))
            .thenReturn(new MatchEffectService.TriggeredEffectPreview(false, List.of(), null, null));
        when(giftTriggerService.previewGiftTriggeredEffectsOnCollab(100L, 10L, 701L, 4))
            .thenReturn(List.of());
        when(eventHookService.onHolomemCollab(100L, 10L, "hBP01-001", 701L))
            .thenReturn(Map.of());

        CollabEffectResolution result = service.resolve(action, resolutionResult);

        assertThat(result.collabEffectSummary()).containsEntry("hasCollabEffect", false);
        assertThat(result.collabEffectSummary()).containsEntry("deferred", false);
        assertThat(result.giftEffectSummary()).isEmpty();
        assertThat(result.hasPendingInteraction()).isFalse();
        assertThat(result.triggerResolutionOrder()).hasSize(2);
    }

    @Test
    void resolveShouldCreateConfirmPendingDecisionWhenCollabEffectExists() {
        CollabAction action = action();
        CollabResolutionResult resolutionResult = resolutionResult();
        when(triggeredCardEffectService.previewCollabTriggeredEffect(100L, 10L, "hBP01-001", 701L))
            .thenReturn(new MatchEffectService.TriggeredEffectPreview(true, List.of("LOOK_TOP_DECK"), "raw", null));
        when(giftTriggerService.previewGiftTriggeredEffectsOnCollab(100L, 10L, 701L, 4))
            .thenReturn(List.of());
        when(eventHookService.onHolomemCollab(100L, 10L, "hBP01-001", 701L))
            .thenReturn(Map.of("triggered", true));
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
                eq("COLLAB"),
                eq(701L),
                eq("hBP01-001"),
                eq("COLLAB_TRIGGER"),
                eq(0),
                eq(0),
                eq("PENDING"),
                any(String.class)
            )
        ).thenReturn(700L);

        CollabEffectResolution result = service.resolve(action, resolutionResult);

        assertThat(result.hasPendingInteraction()).isTrue();
        assertThat(result.pendingInteractionDecisionId()).isEqualTo(700L);
        assertThat(result.pendingInteractionDecisionType()).isEqualTo("TRIGGER_EFFECT_CONFIRM");
        assertThat(result.collabEffectSummary()).containsEntry("hasCollabEffect", true);
        assertThat(result.triggerSummary()).containsEntry("triggered", true);

        verify(jdbcTemplate).query(contains("INSERT INTO match_pending_decisions"), any(ResultSetExtractor.class), eq(100L), eq(10L), eq("TRIGGER_EFFECT_CONFIRM"), eq("COLLAB"), eq(701L), eq("hBP01-001"), eq("COLLAB_TRIGGER"), eq(0), eq(0), eq("PENDING"), any(String.class));
    }

    @Test
    void resolveShouldIncludeSourceAndGiftHolderCardsWhenGiftTriggerExists() {
        CollabAction action = action();
        CollabResolutionResult resolutionResult = resolutionResult();
        Map<String, Object> giftTrigger = Map.of(
            "triggerType",
            "COLLAB",
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
        when(triggeredCardEffectService.previewCollabTriggeredEffect(100L, 10L, "hBP01-001", 701L))
            .thenReturn(new MatchEffectService.TriggeredEffectPreview(false, List.of(), null, null));
        when(giftTriggerService.previewGiftTriggeredEffectsOnCollab(100L, 10L, 701L, 4))
            .thenReturn(List.of(giftTrigger));
        when(eventHookService.onHolomemCollab(100L, 10L, "hBP01-001", 701L))
            .thenReturn(Map.of("triggered", true));
        when(jdbcTemplate.queryForObject(contains("FROM match_pending_decisions"), eq(Integer.class), eq(100L), eq(10L)))
            .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(100L), eq(10L), eq(701L)))
            .thenReturn(null);
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(100L), eq(10L), eq(801L)))
            .thenReturn(null);
        when(
            jdbcTemplate.query(
                contains("INSERT INTO match_pending_decisions"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(10L),
                eq("TRIGGER_EFFECT_CONFIRM"),
                eq("COLLAB"),
                eq(701L),
                eq("hBP01-001"),
                eq("COLLAB_TRIGGER"),
                eq(0),
                eq(0),
                eq("PENDING"),
                contains("\"cards\":[{\"cardInstanceId\":701,\"cardId\":\"hBP01-001\",\"zone\":\"STAGE\"")
            )
        ).thenReturn(7010L);

        CollabEffectResolution result = service.resolve(action, resolutionResult);

        assertThat(result.hasPendingInteraction()).isTrue();
        assertThat(result.pendingInteractionDecisionId()).isEqualTo(7010L);
        assertThat(result.pendingInteractionDecisionType()).isEqualTo("TRIGGER_EFFECT_CONFIRM");
        assertThat(result.giftEffectSummary()).containsEntry("deferred", true);
        assertThat(result.giftEffectSummary()).containsEntry("requestedEffects", List.of("DRAW"));
        verify(jdbcTemplate).query(
            contains("INSERT INTO match_pending_decisions"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(10L),
            eq("TRIGGER_EFFECT_CONFIRM"),
            eq("COLLAB"),
            eq(701L),
            eq("hBP01-001"),
            eq("COLLAB_TRIGGER"),
            eq(0),
            eq(0),
            eq("PENDING"),
            contains("{\"cardInstanceId\":801,\"cardId\":\"hBP06-014\",\"zone\":\"BACK\"")
        );
    }

    private CollabAction action() {
        return new CollabAction(
            "COLLAB",
            100L,
            10L,
            701L,
            "COLLAB",
            4,
            CollabAction.ActionSource.TEST,
            "trace-collab",
            "idem-collab",
            LocalDateTime.now()
        );
    }

    private CollabResolutionResult resolutionResult() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        return new CollabResolutionResult(
            match,
            10L,
            4,
            901L,
            701L,
            "hBP01-001",
            "BACK",
            "COLLAB",
            601L
        );
    }
}
