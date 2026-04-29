package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class FollowupCardCandidateLoaderTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final FollowupCardCandidateLoader loader = new FollowupCardCandidateLoader(jdbcTemplate);

    @Test
    void loadCardCandidateForDecisionShouldPreserveZoneForOwnerViewer() {
        whenQueryReturns(
            Map.of(
                "cardInstanceId",
                101L,
                "cardId",
                "HBP99-001",
                "zone",
                "HAND",
                "name",
                "Test Card",
                "cardType",
                "MEMBER",
                "imageUrl",
                "image.png",
                "levelType",
                "DEBUT"
            )
        );

        Map<String, Object> candidate = loader.loadCardCandidateForDecision(
            1L,
            10L,
            10L,
            101L,
            "DECK",
            "FALLBACK"
        );

        assertThat(candidate).containsEntry("cardInstanceId", 101L);
        assertThat(candidate).containsEntry("cardId", "HBP99-001");
        assertThat(candidate).containsEntry("zone", "HAND");
        assertThat(candidate).containsEntry("name", "Test Card");
        assertThat(candidate).containsEntry("cardType", "MEMBER");
        assertThat(candidate).containsEntry("imageUrl", "image.png");
        assertThat(candidate).containsEntry("levelType", "DEBUT");
    }

    @Test
    void loadCardCandidateForDecisionShouldHideZoneForOtherViewer() {
        whenQueryReturns(
            Map.of(
                "cardInstanceId",
                101L,
                "cardId",
                "HBP99-001",
                "zone",
                "HAND"
            )
        );

        Map<String, Object> candidate = loader.loadCardCandidateForDecision(
            1L,
            10L,
            20L,
            101L,
            "DECK",
            "FALLBACK"
        );

        assertThat(candidate).containsEntry("cardInstanceId", 101L);
        assertThat(candidate).containsEntry("cardId", "HBP99-001");
        assertThat(candidate).containsEntry("zone", null);
    }

    @Test
    void loadCardCandidateForDecisionShouldReturnFallbackWhenMissingRow() {
        whenQueryReturns(null);

        Map<String, Object> candidate = loader.loadCardCandidateForDecision(
            1L,
            10L,
            20L,
            101L,
            " deck ",
            "HBP99-001"
        );

        assertThat(candidate).containsEntry("cardInstanceId", 101L);
        assertThat(candidate).containsEntry("cardId", "HBP99-001");
        assertThat(candidate).containsEntry("zone", "DECK");
        assertThat(candidate).containsEntry("name", null);
        assertThat(candidate).containsEntry("cardType", null);
        assertThat(candidate).containsEntry("imageUrl", null);
        assertThat(candidate).containsEntry("levelType", null);
    }

    @Test
    void loadOwnedCardCandidateForDecisionShouldPreserveZoneForActorOwnedCard() {
        whenQueryReturns(
            Map.of(
                "cardInstanceId",
                101L,
                "cardId",
                "HBP99-001",
                "zone",
                "STAGE"
            )
        );

        Map<String, Object> candidate = loader.loadOwnedCardCandidateForDecision(
            1L,
            10L,
            101L,
            "HAND",
            "FALLBACK"
        );

        assertThat(candidate).containsEntry("cardInstanceId", 101L);
        assertThat(candidate).containsEntry("cardId", "HBP99-001");
        assertThat(candidate).containsEntry("zone", "STAGE");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void whenQueryReturns(Map<String, Object> row) {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
            .thenReturn(row == null ? null : new LinkedHashMap<>(row));
    }
}
