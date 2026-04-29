package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class FollowupInteractionContextResolverTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final FollowupInteractionContextResolver resolver = new FollowupInteractionContextResolver(jdbcTemplate);

    @Test
    void resolveShouldBuildLookTopDeckContextUsingCardCandidateLoader() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any())).thenReturn(null);

        FollowupInteractionContext context = resolver.resolve(
            100L,
            10L,
            Map.of(
                "executedEffects",
                List.of(Map.of(
                    "effectType",
                    "LOOK_TOP_DECK",
                    "applied",
                    true,
                    "lookedCardInstanceId",
                    801L,
                    "lookedCardId",
                    "hBP02-001"
                ))
            )
        );

        assertThat(context).isNotNull();
        assertThat(context.decisionType()).isEqualTo("LOOK_TOP_DECK");
        assertThat(context.candidateCardInstanceIds()).containsExactly(801L);
        assertThat(context.cards()).hasSize(1);
        assertThat(context.cards().get(0))
            .containsEntry("cardInstanceId", 801L)
            .containsEntry("cardId", "hBP02-001")
            .containsEntry("zone", "DECK");
    }

    @Test
    void resolveShouldReturnNullWhenNoFollowupInteractionEffect() {
        FollowupInteractionContext context = resolver.resolve(
            100L,
            10L,
            Map.of("executedEffects", List.of(Map.of("effectType", "DRAW", "applied", true)))
        );

        assertThat(context).isNull();
    }
}
