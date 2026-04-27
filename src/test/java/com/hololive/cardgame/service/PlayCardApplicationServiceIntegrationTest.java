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
class PlayCardApplicationServiceIntegrationTest extends MatchIntegrationTestSupport {

    @Autowired
    private PlayCardApplicationService playCardApplicationService;

    @Test
    void resolveStateShouldMoveHandMemberToStageAndCreateHolomemStack() {
        StartedMatchContext started = createStartedMatch("play-card-app-host", "play-card-app-guest");
        Long matchId = started.matchId();
        Long hostId = started.hostId();
        String cardId = createGeneratedMemberCardDefinition(
            "TPLAY_CARD_TARGET",
            "Bridge Play Card Member",
            "DEBUT",
            60,
            "WHITE"
        );
        Long cardInstanceId = insertCardIntoZone(matchId, hostId, cardId, "HAND", false);
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        PlayCardAction action = PlayCardAction.fromApi(
            matchId,
            hostId,
            cardInstanceId,
            "BACK",
            turnNumber == null ? 1 : turnNumber,
            false,
            "play-card-app-integration"
        );

        PlayCardValidationContext validationContext = playCardApplicationService.validate(action);
        PlayCardResolutionResult result = playCardApplicationService.resolveState(action, validationContext);

        PlayedHolomemRow playedHolomem = jdbcTemplate.query(
            """
            SELECT mc.zone AS card_zone,
                   mc.is_face_down AS card_face_down,
                   h.id AS holomem_id,
                   h.zone AS holomem_zone,
                   h.is_face_down AS holomem_face_down,
                   h.current_level,
                   h.entered_turn_number,
                   s.stack_order
            FROM match_holomems h
            JOIN match_cards mc ON mc.id = h.match_card_id
            JOIN match_holomem_stack_cards s ON s.match_holomem_id = h.id
            WHERE h.id = ?
              AND s.match_card_id = ?
            """,
            rs -> rs.next()
                ? new PlayedHolomemRow(
                    rs.getString("card_zone"),
                    rs.getBoolean("card_face_down"),
                    rs.getLong("holomem_id"),
                    rs.getString("holomem_zone"),
                    rs.getBoolean("holomem_face_down"),
                    rs.getString("current_level"),
                    rs.getInt("entered_turn_number"),
                    rs.getInt("stack_order")
                )
                : null,
            result.matchHolomemId(),
            cardInstanceId
        );

        assertThat(result.cardInstanceId()).isEqualTo(cardInstanceId);
        assertThat(result.cardId()).isEqualTo(cardId);
        assertThat(result.sourceZone()).isEqualTo("HAND");
        assertThat(result.targetZone()).isEqualTo("BACK");
        assertThat(result.matchHolomemId()).isNotNull();
        assertThat(result.faceDown()).isFalse();
        assertThat(playedHolomem).isNotNull();
        assertThat(playedHolomem.cardZone()).isEqualTo("STAGE");
        assertThat(playedHolomem.cardFaceDown()).isFalse();
        assertThat(playedHolomem.holomemId()).isEqualTo(result.matchHolomemId());
        assertThat(playedHolomem.holomemZone()).isEqualTo("BACK");
        assertThat(playedHolomem.holomemFaceDown()).isFalse();
        assertThat(playedHolomem.currentLevel()).isEqualTo("DEBUT");
        assertThat(playedHolomem.enteredTurnNumber()).isEqualTo(result.turnNumber());
        assertThat(playedHolomem.stackOrder()).isEqualTo(1);
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

    private record PlayedHolomemRow(
        String cardZone,
        boolean cardFaceDown,
        Long holomemId,
        String holomemZone,
        boolean holomemFaceDown,
        String currentLevel,
        int enteredTurnNumber,
        int stackOrder
    ) {
    }
}
