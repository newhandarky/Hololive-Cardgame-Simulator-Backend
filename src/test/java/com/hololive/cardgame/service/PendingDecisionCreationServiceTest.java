package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class PendingDecisionCreationServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PendingDecisionReader pendingDecisionReader = mock(PendingDecisionReader.class);
    private final PendingDecisionCreationService service = new PendingDecisionCreationService(
        jdbcTemplate,
        new MatchPayloadJsonService(new ObjectMapper()),
        pendingDecisionReader
    );

    @Test
    void createTurnStartPendingInteractionShouldSkipWhenUserAlreadyHasPendingDecision() {
        when(pendingDecisionReader.hasAnyPendingDecision(100L, 10L)).thenReturn(true);

        Long decisionId = service.createTurnStartPendingInteraction(100L, 10L, 3);

        assertThat(decisionId).isNull();
        verify(jdbcTemplate, never()).query(any(String.class), any(ResultSetExtractor.class), any());
    }

    @Test
    void createSendCheerPendingInteractionShouldBuildSourceAndCandidatePayload() throws Exception {
        ResultSet sourceRs = mock(ResultSet.class);
        when(sourceRs.next()).thenReturn(true);
        when(sourceRs.getLong("card_instance_id")).thenReturn(500L);
        when(sourceRs.getString("card_id")).thenReturn("hY01-001");
        when(sourceRs.getString("zone")).thenReturn("CHEER_DECK");
        when(sourceRs.getString("name")).thenReturn("White Cheer");
        when(sourceRs.getString("card_type")).thenReturn("CHEER");
        when(sourceRs.getString("image_url")).thenReturn("cheer.png");
        when(jdbcTemplate.query(
            contains("FROM match_cards mc"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(10L),
            eq(500L)
        )).thenAnswer(invocation -> {
            ResultSetExtractor<Map<String, Object>> extractor = invocation.getArgument(1);
            return extractor.extractData(sourceRs);
        });
        when(jdbcTemplate.queryForObject(
            contains("FROM cheer_cards"),
            eq(Integer.class),
            eq("hY01-001")
        )).thenReturn(1);
        when(jdbcTemplate.queryForList(
            contains("FROM match_holomems h"),
            eq(100L),
            eq(10L)
        )).thenReturn(List.of(
            Map.of(
                "card_instance_id", 900L,
                "card_id", "hBP01-001",
                "zone", "CENTER",
                "name", "Tokino Sora",
                "card_type", "HOLOMEM",
                "level_type", "DEBUT",
                "image_url", "center.png"
            )
        ));
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(
            contains("INSERT INTO match_pending_decisions"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(10L),
            eq("SEND_CHEER"),
            eq("TURN_CHEER"),
            eq(500L),
            eq("hY01-001"),
            eq("SEND_CHEER"),
            eq(PendingDecisionReader.PENDING_STATUS),
            contextCaptor.capture()
        )).thenReturn(777L);

        Long decisionId = service.createSendCheerPendingInteraction(
            100L,
            10L,
            500L,
            "TURN_CHEER",
            "回合吶喊",
            "請從エール牌庫發送 1 張吶喊到我方 Holomem。"
        );

        assertThat(decisionId).isEqualTo(777L);
        assertThat(contextCaptor.getValue()).contains("\"interactionType\":\"SEND_CHEER\"");
        assertThat(contextCaptor.getValue()).contains("\"sourceZone\":\"CHEER_DECK\"");
        assertThat(contextCaptor.getValue()).contains("\"candidateCardInstanceIds\":[900]");
        assertThat(contextCaptor.getValue()).contains("\"levelType\":\"DEBUT\"");
    }

    @Test
    void createTurnSendCheerPendingInteractionShouldUseResolvedAvailabilityWithoutRequeryingSource() {
        TurnCheerAvailabilityService.TurnCheerAvailability availability =
            new TurnCheerAvailabilityService.TurnCheerAvailability(
                500L,
                "hY01-001",
                "CHEER_DECK",
                List.of(new TurnCheerAvailabilityService.TurnCheerTarget(
                    900L,
                    "hBP01-001",
                    "Tokino Sora",
                    "HOLOMEM",
                    "DEBUT",
                    "CENTER",
                    "center.png"
                ))
            );
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(
            contains("INSERT INTO match_pending_decisions"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(10L),
            eq("SEND_CHEER"),
            eq("TURN_CHEER"),
            eq(500L),
            eq("hY01-001"),
            eq("SEND_CHEER"),
            eq(PendingDecisionReader.PENDING_STATUS),
            contextCaptor.capture()
        )).thenReturn(777L);

        Long decisionId = service.createTurnSendCheerPendingInteraction(100L, 10L, availability);

        assertThat(decisionId).isEqualTo(777L);
        assertThat(contextCaptor.getValue()).contains("\"sourceZone\":\"CHEER_DECK\"");
        assertThat(contextCaptor.getValue()).contains("\"candidateCardInstanceIds\":[900]");
    }

    @Test
    void createCardSelectionPendingDecisionShouldRejectWhenBlockingPendingExists() {
        when(pendingDecisionReader.hasBlockingPendingDecision(100L, 10L)).thenReturn(true);
        MatchEffectService.SupportDecisionPlan decisionPlan = new MatchEffectService.SupportDecisionPlan(
            "DRAW",
            1,
            1,
            List.of()
        );

        assertThatThrownBy(() -> service.createCardSelectionPendingDecision(
            100L,
            10L,
            "PLAY_SUPPORT",
            500L,
            "hBP01-001",
            "DRAW",
            "{}",
            "SELF",
            null,
            decisionPlan,
            true
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("待處理的效果選擇");

        verify(jdbcTemplate, never()).query(contains("INSERT INTO match_pending_decisions"), any(ResultSetExtractor.class), any());
    }
}
