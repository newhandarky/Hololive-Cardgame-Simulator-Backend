package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.error.GameErrorCode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MatchControllerEndTurnApiIntegrationTest extends MatchIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Override
    protected void executeRequiredTurnActions(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        resolvePendingInteractionIfExists(matchId, userId, "TURN_START");
        try {
            matchActionService.drawTurn(matchId, userId);
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
            matchActionService.sendTurnCheer(matchId, userId);
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
        Long sendCheerDecisionId = jdbcTemplate.query(
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
        if (sendCheerDecisionId == null) {
            return;
        }
        Long effectiveTargetCardInstanceId = sendCheerTargetCardInstanceId == null
            ? loadFirstStageCardInstanceId(matchId, userId)
            : sendCheerTargetCardInstanceId;
        if (effectiveTargetCardInstanceId == null) {
            return;
        }
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(sendCheerDecisionId);
        request.setSelectedCardInstanceIds(List.of(effectiveTargetCardInstanceId));
        matchActionService.resolveDecision(matchId, userId, request);
    }

    @Test
    void endTurnApiShouldFallbackToCurrentTurnSnapshotWhenRequestBodyMissing() throws Exception {
        StartedMatchContext context = createStartedMatch("end-api-fallback-host", "end-api-fallback-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        advanceToEndPhase(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));

        mockMvc.perform(
                post("/api/matches/{matchId}/actions/end-turn", matchId)
                    .header("Authorization", bearerTokenFor(hostId))
            )
            .andExpect(status().isOk());

        Long currentTurnPlayerId = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        assertThat(currentTurnPlayerId).isEqualTo(guestId);

        Integer pendingTurnStart = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            """,
            Integer.class,
            matchId,
            guestId
        );
        assertThat(pendingTurnStart).isEqualTo(1);
    }

    @Test
    void endTurnApiShouldReturnStaleActionWhenRequestedTurnNumberIsOld() throws Exception {
        StartedMatchContext context = createStartedMatch("end-api-stale-host", "end-api-stale-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        advanceToEndPhase(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        matchActionService.endTurn(matchId, hostId);

        Integer staleTurnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM match_actions WHERE match_id = ? AND user_id = ? AND action_type = 'END_TURN' ORDER BY id DESC LIMIT 1",
            Integer.class,
            matchId,
            hostId
        );
        Integer currentTurnNumber = jdbcTemplate.queryForObject("SELECT turn_number FROM matches WHERE id = ?", Integer.class, matchId);
        assertThat(staleTurnNumber).isNotNull();
        assertThat(currentTurnNumber).isEqualTo(staleTurnNumber + 1);

        Integer guestTurnStartPending = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            """,
            Integer.class,
            matchId,
            guestId
        );
        assertThat(guestTurnStartPending).isEqualTo(1);

        String requestBody = objectMapper.writeValueAsString(
            new LinkedHashMap<>(java.util.Map.of(
                "requestedTurnNumber", staleTurnNumber,
                "idempotencyKey", "stale-end-turn-" + matchId
            ))
        );

        mockMvc.perform(
                post("/api/matches/{matchId}/actions/end-turn", matchId)
                    .header("Authorization", bearerTokenFor(hostId))
                    .contentType(APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(GameErrorCode.STALE_ACTION.name()))
            .andExpect(jsonPath("$.details.requestedTurnNumber").value(staleTurnNumber))
            .andExpect(jsonPath("$.details.currentTurnNumber").value(currentTurnNumber));
    }

    @Test
    void endTurnApiShouldReturnDuplicateActionWhenSameIdempotencyKeyAlreadyExistsForCurrentTurn() throws Exception {
        StartedMatchContext context = createStartedMatch("end-api-duplicate-host", "end-api-duplicate-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        advanceToEndPhase(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        Integer currentTurnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        assertThat(currentTurnNumber).isNotNull();

        String idempotencyKey = "duplicate-end-turn-" + matchId;
        seedExistingEndTurnAction(matchId, hostId, currentTurnNumber, idempotencyKey);

        String requestBody = objectMapper.writeValueAsString(
            new LinkedHashMap<>(java.util.Map.of(
                "requestedTurnNumber", currentTurnNumber,
                "idempotencyKey", idempotencyKey
            ))
        );

        mockMvc.perform(
                post("/api/matches/{matchId}/actions/end-turn", matchId)
                    .header("Authorization", bearerTokenFor(hostId))
                    .contentType(APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(GameErrorCode.DUPLICATE_ACTION.name()));
    }

    @Test
    void endTurnApiShouldExposeConsistentStateForNextTurnPlayerAfterResponse() throws Exception {
        StartedMatchContext context = createStartedMatch("end-api-visible-host", "end-api-visible-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        advanceToEndPhase(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));

        mockMvc.perform(
                post("/api/matches/{matchId}/actions/end-turn", matchId)
                    .header("Authorization", bearerTokenFor(hostId))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentTurnPlayerId").value(guestId))
            .andExpect(jsonPath("$.turnNumber").value(2));

        mockMvc.perform(
                get("/api/matches/{matchId}/state", matchId)
                    .header("Authorization", bearerTokenFor(guestId))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentTurnPlayerId").value(guestId))
            .andExpect(jsonPath("$.turnNumber").value(2))
            .andExpect(jsonPath("$.phase").value("MAIN"))
            .andExpect(jsonPath("$.pendingInteractions[0].interactionType").value("TURN_START"))
            .andExpect(jsonPath("$.pendingInteractions[0].sourceActionType").value("TURN_START"))
            .andExpect(jsonPath("$.recentActions[*].actionType", hasItem("END_TURN")));
    }

    private String bearerTokenFor(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + jwtTokenProvider.generateToken(userId, user.getLineUserId());
    }

    private void advanceToEndPhase(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        executeRequiredTurnActions(matchId, userId, sendCheerTargetCardInstanceId);
        while (true) {
            String phase = jdbcTemplate.queryForObject(
                "SELECT current_phase FROM matches WHERE id = ?",
                String.class,
                matchId
            );
            if ("END".equals(phase)) {
                return;
            }
            if (!"MAIN".equals(phase) && !"PERFORMANCE".equals(phase)) {
                throw new IllegalStateException("無法推進至 END，當前 phase=" + phase);
            }
            matchActionService.advancePhase(matchId, userId);
        }
    }

    private void seedExistingEndTurnAction(Long matchId, Long userId, int turnNumber, String idempotencyKey)
        throws Exception {
        Integer nextActionOrder = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(action_order), 0) + 1 FROM match_actions WHERE match_id = ? AND turn_number = ?",
            Integer.class,
            matchId,
            turnNumber
        );
        String payload = objectMapper.writeValueAsString(
            new LinkedHashMap<>(java.util.Map.of(
                "fromUserId", userId,
                "nextTurnNumber", turnNumber + 1,
                "traceId", "seed-duplicate-trace",
                "idempotencyKey", idempotencyKey
            ))
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (
                match_id,
                user_id,
                turn_number,
                action_order,
                action_type,
                payload,
                executed_at
            ) VALUES (?, ?, ?, ?, 'END_TURN', ?::jsonb, ?)
            """,
            matchId,
            userId,
            turnNumber,
            nextActionOrder,
            payload,
            LocalDateTime.now()
        );
    }
}
