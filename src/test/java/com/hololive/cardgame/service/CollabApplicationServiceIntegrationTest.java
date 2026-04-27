package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CollabApplicationServiceIntegrationTest extends MatchIntegrationTestSupport {

    @Autowired
    private CollabApplicationService collabApplicationService;

    @Test
    void resolveStateShouldMoveBackHolomemToCollabAndTopDeckCardToHolopower() {
        StartedMatchContext started = createStartedMatch("collab-app-host", "collab-app-guest");
        Long matchId = started.matchId();
        Long hostId = started.hostId();
        String backCardId = createGeneratedMemberCardDefinition(
            "TCOLLAB_APP_BACK",
            "Bridge Collab Member",
            "DEBUT",
            60,
            "WHITE"
        );
        Long sourceCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            backCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long topDeckCardInstanceId = loadTopDeckCardInstanceId(matchId, hostId);
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        CollabAction action = CollabAction.fromApi(
            matchId,
            hostId,
            sourceCardInstanceId,
            turnNumber == null ? 1 : turnNumber,
            "collab-app-integration"
        );

        CollabValidationContext validationContext = collabApplicationService.validate(action);
        CollabResolutionResult result = collabApplicationService.resolveState(action, validationContext);

        String sourceZone = jdbcTemplate.queryForObject(
            """
            SELECT zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            String.class,
            matchId,
            hostId,
            sourceCardInstanceId
        );
        HolopowerRow holopowerRow = jdbcTemplate.query(
            """
            SELECT zone, is_face_down
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            rs -> rs.next()
                ? new HolopowerRow(rs.getString("zone"), rs.getBoolean("is_face_down"))
                : null,
            topDeckCardInstanceId,
            matchId,
            hostId
        );

        assertThat(topDeckCardInstanceId).isNotNull();
        assertThat(sourceZone).isEqualTo("COLLAB");
        assertThat(result.sourceCardInstanceId()).isEqualTo(sourceCardInstanceId);
        assertThat(result.sourceCardId()).isEqualTo(backCardId);
        assertThat(result.sourceZone()).isEqualTo("BACK");
        assertThat(result.targetZone()).isEqualTo("COLLAB");
        assertThat(result.holopowerCardInstanceId()).isEqualTo(topDeckCardInstanceId);
        assertThat(holopowerRow).isNotNull();
        assertThat(holopowerRow.zone()).isEqualTo("HOLOPOWER");
        assertThat(holopowerRow.faceDown()).isFalse();
    }

    @Override
    protected void executeRequiredTurnActions(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        resolvePendingInteractionIfExists(matchId, userId, "TURN_START");
        try {
            matchActionService.drawTurn(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
            if (!(ex instanceof GameRuleException gameRuleException)
                || gameRuleException.getCode() != GameErrorCode.TURN_DRAW_ALREADY_USED) {
                String message = ex.getMessage();
                if (message == null || (!message.contains("phase=END") && !message.contains("已經抽過卡"))) {
                    throw ex;
                }
            }
        }
        resolvePendingInteractionIfExists(matchId, userId, "DRAW_REVEAL");
        try {
            matchActionService.sendTurnCheer(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
            if (ex instanceof GameRuleException gameRuleException
                && gameRuleException.getCode() == GameErrorCode.TURN_CHEER_ALREADY_USED) {
                return;
            }
            String message = ex.getMessage();
            if (message == null || (!message.contains("目前無法發送吶喊") && !message.contains("已經發送過吶喊"))) {
                throw ex;
            }
            return;
        }
        Long sendCheerDecisionId = findPendingDecision(matchId, userId, "SEND_CHEER");
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

    private Long loadTopDeckCardInstanceId(Long matchId, Long ownerUserId) {
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId
        );
    }

    private record HolopowerRow(String zone, boolean faceDown) {
    }
}
