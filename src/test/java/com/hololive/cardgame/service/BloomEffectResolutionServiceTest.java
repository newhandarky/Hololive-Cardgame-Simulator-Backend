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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class BloomEffectResolutionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchTriggeredCardEffectService triggeredCardEffectService = mock(MatchTriggeredCardEffectService.class);
    private final MatchEventHookService matchEventHookService = mock(MatchEventHookService.class);
    private final BloomEffectResolutionService service = new BloomEffectResolutionService(
        jdbcTemplate,
        new ObjectMapper(),
        triggeredCardEffectService,
        matchEventHookService
    );

    @Test
    void resolveAfterBloomShouldBuildNonDeferredPayloadWithoutPendingDecisionWhenNoEffect() {
        BloomTargetSnapshot target = new BloomTargetSnapshot(
            901L,
            801L,
            "hBP01-001",
            "Tokino Sora",
            "DEBUT",
            "CENTER",
            10,
            1,
            null,
            false,
            null,
            false
        );
        when(triggeredCardEffectService.applyPassiveGiftExtraBloomAllowanceOnBloom(100L, 10L, 901L, 701L, "hBP01-002"))
            .thenReturn(Map.of("applied", false));
        when(triggeredCardEffectService.previewBloomTriggeredEffect(100L, 10L, "hBP01-002", 701L, "DEBUT"))
            .thenReturn(new MatchEffectService.TriggeredEffectPreview(false, java.util.List.of(), null, null));
        when(matchEventHookService.onHolomemBloom(100L, 10L, "hBP01-002", 701L, 801L, "CENTER"))
            .thenReturn(Map.of("triggered", false));

        BloomEffectResolution result = service.resolveAfterBloom(100L, 10L, 4, 701L, "hBP01-002", target);

        assertThat(result.deferredEffect()).isFalse();
        assertThat(result.pendingInteractionDecisionId()).isNull();
        assertThat(result.bloomEffectSummary())
            .containsEntry("hasBloomEffect", false)
            .containsEntry("deferred", false);
        assertThat(result.triggerResolutionOrder()).hasSize(2);
        verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolveAfterBloomShouldCreateConfirmPendingDecisionWhenBloomEffectExists() {
        BloomTargetSnapshot target = new BloomTargetSnapshot(
            901L,
            801L,
            "hBP01-001",
            "Tokino Sora",
            "DEBUT",
            "CENTER",
            10,
            1,
            null,
            false,
            null,
            false
        );
        when(triggeredCardEffectService.applyPassiveGiftExtraBloomAllowanceOnBloom(100L, 10L, 901L, 701L, "hBP01-002"))
            .thenReturn(Map.of("applied", false));
        when(triggeredCardEffectService.previewBloomTriggeredEffect(100L, 10L, "hBP01-002", 701L, "DEBUT"))
            .thenReturn(new MatchEffectService.TriggeredEffectPreview(true, List.of("DRAW"), "raw bloom text", null));
        when(matchEventHookService.onHolomemBloom(100L, 10L, "hBP01-002", 701L, 801L, "CENTER"))
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
                eq("BLOOM"),
                eq(701L),
                eq("hBP01-002"),
                eq("BLOOM_EFFECT"),
                eq(0),
                eq(0),
                eq("PENDING"),
                contains("\"sourceLevelType\":\"DEBUT\"")
            )
        ).thenReturn(910L);

        BloomEffectResolution result = service.resolveAfterBloom(100L, 10L, 4, 701L, "hBP01-002", target);

        assertThat(result.deferredEffect()).isTrue();
        assertThat(result.pendingInteractionDecisionId()).isEqualTo(910L);
        assertThat(result.pendingInteractionDecisionType()).isEqualTo("TRIGGER_EFFECT_CONFIRM");
        assertThat(result.bloomEffectSummary())
            .containsEntry("hasBloomEffect", true)
            .containsEntry("deferred", true)
            .containsEntry("requestedEffects", List.of("DRAW"));
        assertThat(result.triggerSummary()).containsEntry("triggered", true);
        verify(jdbcTemplate).query(
            contains("INSERT INTO match_pending_decisions"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(10L),
            eq("TRIGGER_EFFECT_CONFIRM"),
            eq("BLOOM"),
            eq(701L),
            eq("hBP01-002"),
            eq("BLOOM_EFFECT"),
            eq(0),
            eq(0),
            eq("PENDING"),
            contains("\"cards\":[{\"cardInstanceId\":701,\"cardId\":\"hBP01-002\",\"zone\":\"STAGE\"")
        );
    }
}
