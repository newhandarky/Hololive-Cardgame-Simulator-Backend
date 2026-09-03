package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.dto.LobbyEvent;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.websocket.MatchSocketHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MatchControllerConcedeApiIntegrationTest extends MatchIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private MatchSocketHandler matchSocketHandler;

    @Override
    protected void executeRequiredTurnActions(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        resolvePendingInteractionIfExists(matchId, userId, "TURN_START");
        try {
            executeDrawTurn(matchId, userId);
        } catch (IllegalStateException | com.hololive.cardgame.error.GameRuleException ex) {
            if (ex instanceof com.hololive.cardgame.error.GameRuleException gameRuleException
                && gameRuleException.getCode() == GameErrorCode.TURN_DRAW_ALREADY_USED) {
                // keep going; turn cheer may still be required.
            } else {
                String message = ex.getMessage();
                if (message != null && message.contains("phase=END")) {
                    return;
                }
                if (message != null && message.contains("已經抽過卡")) {
                    // keep going; turn cheer may still be required.
                } else {
                    throw ex;
                }
            }
        }
        resolvePendingInteractionIfExists(matchId, userId, "DRAW_REVEAL");
        try {
            executeSendTurnCheer(matchId, userId);
        } catch (IllegalStateException | com.hololive.cardgame.error.GameRuleException ex) {
            if (ex instanceof com.hololive.cardgame.error.GameRuleException gameRuleException
                && gameRuleException.getCode() == GameErrorCode.TURN_CHEER_ALREADY_USED) {
                return;
            }
            String message = ex.getMessage();
            if (message != null && message.contains("目前無法發送吶喊")) {
                return;
            }
            if (message != null && message.contains("已經發送過吶喊")) {
                return;
            }
            throw ex;
        }
        Long decisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'SEND_CHEER'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (decisionId == null) {
            return;
        }
        Long targetCardInstanceId = sendCheerTargetCardInstanceId == null
            ? loadFirstStageCardInstanceId(matchId, userId)
            : sendCheerTargetCardInstanceId;
        if (targetCardInstanceId == null) {
            return;
        }
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(decisionId);
        request.setSelectedCardInstanceIds(List.of(targetCardInstanceId));
        matchActionService.resolveDecision(matchId, userId, request);
    }

    @Test
    void concedeApiShouldKeepResponseContractAndFinishMatch() throws Exception {
        StartedMatchContext context = createStartedMatch("concede-api-host", "concede-api-guest");

        mockMvc.perform(
                post("/api/matches/{matchId}/actions/concede", context.matchId())
                    .header("Authorization", bearerTokenFor(context.guestId()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matchId").value(context.matchId()))
            .andExpect(jsonPath("$.status").value("STARTED"))
            .andExpect(jsonPath("$.currentTurnPlayerId").doesNotExist());

        ArgumentCaptor<LobbyEvent> eventCaptor = ArgumentCaptor.forClass(LobbyEvent.class);
        verify(matchSocketHandler).publish(eq(context.matchId()), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("CONCEDE");

        mockMvc.perform(
                post("/api/matches/{matchId}/actions/concede", context.matchId())
                    .header("Authorization", bearerTokenFor(context.guestId()))
            )
            .andExpect(status().isConflict());

        String matchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        Long winnerUserId = jdbcTemplate.queryForObject(
            "SELECT winner_user_id FROM matches WHERE id = ?",
            Long.class,
            context.matchId()
        );
        assertThat(matchStatus).isEqualTo("finished");
        assertThat(winnerUserId).isEqualTo(context.hostId());
    }

    private String bearerTokenFor(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + jwtTokenProvider.generateToken(userId, user.getLineUserId());
    }
}
