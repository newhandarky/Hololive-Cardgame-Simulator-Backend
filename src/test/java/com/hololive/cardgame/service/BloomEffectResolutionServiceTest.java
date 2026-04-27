package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
