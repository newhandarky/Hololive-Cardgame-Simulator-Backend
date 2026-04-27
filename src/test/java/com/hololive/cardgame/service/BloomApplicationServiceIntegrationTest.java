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
class BloomApplicationServiceIntegrationTest extends MatchIntegrationTestSupport {

    @Autowired
    private BloomApplicationService bloomApplicationService;

    @Test
    void resolveStateShouldMoveHandCardToStageAppendStackAndUpdateHolomemTopCard() {
        StartedMatchContext started = createStartedMatch("bloom-app-host", "bloom-app-guest");
        Long matchId = started.matchId();
        Long hostId = started.hostId();
        String debutCardId = createGeneratedMemberCardDefinition(
            "TBLOOM_APP_DEBUT",
            "Bridge Bloom Member",
            "DEBUT",
            50,
            "WHITE"
        );
        String firstCardId = createGeneratedMemberCardDefinition(
            "TBLOOM_APP_FIRST",
            "Bridge Bloom Member",
            "FIRST",
            80,
            "WHITE"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        BloomAction action = BloomAction.fromApi(
            matchId,
            hostId,
            bloomCardInstanceId,
            targetCardInstanceId,
            turnNumber == null ? 1 : turnNumber,
            "bloom-app-integration"
        );

        BloomValidationContext validationContext = bloomApplicationService.validate(action);
        BloomResolutionResult result = bloomApplicationService.resolveState(action, validationContext);

        BloomTopRow top = jdbcTemplate.query(
            """
            SELECT match_card_id, card_id, current_level, last_bloom_turn
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            LIMIT 1
            """,
            rs -> rs.next()
                ? new BloomTopRow(
                    rs.getLong("match_card_id"),
                    rs.getString("card_id"),
                    rs.getString("current_level"),
                    rs.getInt("last_bloom_turn")
                )
                : null,
            matchId,
            hostId,
            result.targetHolomemId()
        );
        Integer stackDepth = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_stack_cards WHERE match_holomem_id = ?",
            Integer.class,
            result.targetHolomemId()
        );

        assertThat(top).isNotNull();
        assertThat(top.matchCardId()).isEqualTo(bloomCardInstanceId);
        assertThat(top.cardId()).isEqualTo(firstCardId);
        assertThat(top.currentLevel()).isEqualTo("FIRST");
        assertThat(top.lastBloomTurn()).isEqualTo(turnNumber);
        assertThat(stackDepth).isEqualTo(2);
        assertThat(result.stackDepth()).isEqualTo(2);
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

    private Long insertCardIntoHand(Long matchId, Long ownerUserId, String cardId) {
        return insertCardIntoZone(matchId, ownerUserId, cardId, "HAND", false);
    }

    private record BloomTopRow(Long matchCardId, String cardId, String currentLevel, int lastBloomTurn) {
    }
}
